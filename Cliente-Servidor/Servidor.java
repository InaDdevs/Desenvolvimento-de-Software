import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor do SiCA (Sistema de Compartilhamento de Arquivos).
 *
 * <p>Fica aguardando conexões TCP e, para cada cliente conectado, atende os comandos
 * {@code LISTAR}, {@code ENVIAR} (upload) e {@code BAIXAR} (download) definidos em
 * {@link Protocolo}. Os arquivos ficam guardados em uma única pasta do servidor.
 *
 * <p>Uso: {@code java Servidor [porta] [pasta]} (padrões: 5000 e "arquivos_servidor").
 *
 * <p>Cada cliente é atendido em sua própria thread, então vários podem usar o servidor
 * ao mesmo tempo sem que um bloqueie o outro.
 */
public class Servidor {

    public static void main(String[] args) throws IOException {
        int porta = args.length > 0 ? Integer.parseInt(args[0]) : Protocolo.PORTA_PADRAO;
        Path pasta = Paths.get(args.length > 1 ? args[1] : "arquivos_servidor").toAbsolutePath();
        Files.createDirectories(pasta);

        // Pool de threads: reaproveita threads de clientes que já saíram.
        ExecutorService threads = Executors.newCachedThreadPool();

        try (ServerSocket servidor = new ServerSocket(porta)) {
            System.out.println("SiCA servidor ouvindo na porta " + porta + ", pasta: " + pasta);
            while (true) {
                // accept() bloqueia até um cliente conectar e devolve o socket dessa conexão.
                Socket cliente = servidor.accept();
                threads.execute(new AtendimentoCliente(cliente, pasta));
            }
        } finally {
            threads.shutdown();
        }
    }

    /**
     * Atende um único cliente durante toda a vida da sua conexão.
     * Uma instância é executada por thread, então os campos abaixo nunca são compartilhados.
     */
    private static class AtendimentoCliente implements Runnable {
        private final Socket socket;
        private final Path pasta;
        private final String id; // "ip:porta" do cliente, usado nos logs
        private DataInputStream in;
        private DataOutputStream out;

        AtendimentoCliente(Socket socket, Path pasta) {
            this.socket = socket;
            this.pasta = pasta;
            this.id = socket.getRemoteSocketAddress().toString();
        }

        /**
         * Ponto de entrada da thread: abre os fluxos do socket, atende os comandos e,
         * ao final (por qualquer motivo), garante o fechamento da conexão.
         */
        @Override
        public void run() {
            log("conectado");
            // try-with-resources fecha o socket (e com ele os fluxos) ao sair do bloco.
            try (Socket s = socket) {
                // Os fluxos com buffer reduzem o número de pacotes pequenos na rede.
                // Por isso é obrigatório chamar flush() depois de cada resposta.
                in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                atender();
            } catch (IOException e) {
                log("conexão encerrada com erro: " + e.getMessage());
            }
            log("desconectado");
        }

        /**
         * Laço principal: lê um comando, executa e volta a esperar o próximo,
         * até o cliente enviar SAIR ou fechar a conexão.
         */
        private void atender() throws IOException {
            while (true) {
                String comando;
                try {
                    comando = in.readUTF();
                } catch (EOFException e) {
                    return; // o cliente fechou a conexão sem enviar SAIR
                }
                log("comando " + comando);

                switch (comando) {
                    case Protocolo.CMD_LISTAR:
                        listar();
                        break;
                    case Protocolo.CMD_ENVIAR:
                        receberArquivo();
                        break;
                    case Protocolo.CMD_BAIXAR:
                        enviarArquivo();
                        break;
                    case Protocolo.CMD_SAIR:
                        return;
                    default:
                        // Não dá para saber quantos bytes de argumentos vieram junto com um
                        // comando desconhecido; é mais seguro avisar e encerrar a conexão.
                        Protocolo.enviarErro(out, "Comando desconhecido: " + comando);
                        return;
                }
            }
        }

        /**
         * Comando LISTAR: responde com a quantidade de arquivos e, para cada um,
         * o nome e o tamanho em bytes. Subpastas e arquivos ocultos (como os
         * temporários de upload) não aparecem.
         */
        private void listar() throws IOException {
            List<Path> arquivos = new ArrayList<>();
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(pasta)) {
                for (Path p : dir) {
                    if (Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) {
                        arquivos.add(p);
                    }
                }
            }
            Collections.sort(arquivos); // ordem alfabética, para a saída ser previsível

            Protocolo.enviarOk(out);
            out.writeInt(arquivos.size());
            for (Path p : arquivos) {
                out.writeUTF(p.getFileName().toString());
                out.writeLong(Files.size(p));
            }
            out.flush();
        }

        /**
         * Comando ENVIAR (upload do ponto de vista do cliente): recebe um arquivo e o grava na pasta.
         *
         * <p>Passos: (1) lê nome e tamanho; (2) valida e responde OK ou ERRO, <i>antes</i> de
         * o cliente despejar os bytes; (3) recebe os bytes em um arquivo temporário; (4) só
         * quando o arquivo está completo, renomeia para o nome final. Assim, um upload
         * interrompido nunca deixa um arquivo pela metade visível na lista, e quem está
         * baixando um arquivo de mesmo nome nunca vê conteúdo parcial.
         * Se já existir um arquivo com o mesmo nome, ele é substituído.
         */
        private void receberArquivo() throws IOException {
            String nome = in.readUTF();
            long tamanho = in.readLong();

            if (!Protocolo.nomeValido(nome)) {
                Protocolo.enviarErro(out, "Nome de arquivo inválido: " + nome);
                return;
            }
            if (tamanho < 0) {
                Protocolo.enviarErro(out, "Tamanho inválido: " + tamanho);
                return;
            }

            Protocolo.enviarOk(out); // autoriza o cliente a enviar os bytes

            Path temporario = Files.createTempFile(pasta, ".upload-", ".tmp");
            try {
                try (OutputStream arquivo = Files.newOutputStream(temporario)) {
                    Protocolo.copiar(in, arquivo, tamanho);
                }
                Files.move(temporario, pasta.resolve(nome), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(temporario); // não deixa lixo se o upload falhar
                throw e;
            }

            log("recebido " + nome + " (" + tamanho + " bytes)");
            Protocolo.enviarOk(out); // confirma que o arquivo foi gravado por completo
        }

        /**
         * Comando BAIXAR (download do ponto de vista do cliente): envia ao cliente o arquivo pedido.
         *
         * <p>Responde ERRO se o nome for inválido ou o arquivo não existir. Caso contrário
         * envia OK, o tamanho (long) e depois os bytes do arquivo.
         */
        private void enviarArquivo() throws IOException {
            String nome = in.readUTF();

            if (!Protocolo.nomeValido(nome)) {
                Protocolo.enviarErro(out, "Nome de arquivo inválido: " + nome);
                return;
            }
            Path caminho = pasta.resolve(nome);
            if (!Files.isRegularFile(caminho)) {
                Protocolo.enviarErro(out, "Arquivo não encontrado: " + nome);
                return;
            }

            // O tamanho é lido do mesmo canal aberto para a leitura. Se outro cliente
            // substituir o arquivo durante o envio, continuamos lendo a versão que abrimos,
            // então tamanho e conteúdo permanecem coerentes entre si.
            try (FileChannel canal = FileChannel.open(caminho, StandardOpenOption.READ)) {
                long tamanho = canal.size();
                Protocolo.enviarOk(out);
                out.writeLong(tamanho);
                InputStream arquivo = Channels.newInputStream(canal);
                Protocolo.copiar(arquivo, out, tamanho);
                out.flush();
                log("enviado " + nome + " (" + tamanho + " bytes)");
            } catch (java.nio.file.NoSuchFileException e) {
                // O arquivo sumiu entre a verificação e a abertura (corrida rara).
                Protocolo.enviarErro(out, "Arquivo não encontrado: " + nome);
            }
        }

        private void log(String mensagem) {
            System.out.println("[" + id + "] " + mensagem);
        }
    }
}
