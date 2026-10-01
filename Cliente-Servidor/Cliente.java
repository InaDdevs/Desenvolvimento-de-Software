import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;

/**
 * Cliente do SiCA (Sistema de Compartilhamento de Arquivos).
 *
 * <p>Conecta-se ao {@link Servidor} e oferece um menu de console com as operações
 * listar, enviar e baixar arquivos. A conexão fica aberta durante toda a sessão.
 *
 * <p>Uso: {@code java Cliente [host] [porta] [pasta_de_downloads]}
 * (padrões: localhost, 5000 e "downloads").
 */
public class Cliente {

    private final DataInputStream in;
    private final DataOutputStream out;
    private final Path pastaDownloads;

    Cliente(Socket socket, Path pastaDownloads) throws IOException {
        // Fluxos com buffer; lembre-se de chamar flush() após cada mensagem enviada.
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.pastaDownloads = pastaDownloads;
    }

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : Protocolo.PORTA_PADRAO;
        Path pasta = Paths.get(args.length > 2 ? args[2] : "downloads");
        Files.createDirectories(pasta);

        try (Socket socket = new Socket(host, porta);
             Scanner teclado = new Scanner(System.in)) {
            System.out.println("Conectado a " + host + ":" + porta);
            new Cliente(socket, pasta).menu(teclado);
        } catch (java.net.ConnectException e) {
            System.err.println("Não foi possível conectar a " + host + ":" + porta
                    + " (o servidor está no ar?)");
        }
    }

    /**
     * Laço do menu: mostra as opções, lê a escolha e chama a operação correspondente.
     * Erros de rede (IOException) encerram a sessão; erros de uso (arquivo inexistente,
     * nome inválido...) apenas são mostrados e o menu continua.
     */
    void menu(Scanner teclado) {
        try {
            while (true) {
                System.out.println("\n1) Listar arquivos do servidor");
                System.out.println("2) Enviar arquivo");
                System.out.println("3) Baixar arquivo");
                System.out.println("0) Sair");
                System.out.print("Opção: ");
                if (!teclado.hasNextLine()) {
                    break; // fim da entrada (Ctrl+D)
                }
                switch (teclado.nextLine().trim()) {
                    case "1":
                        listar();
                        break;
                    case "2":
                        System.out.print("Caminho do arquivo local: ");
                        enviar(Paths.get(teclado.nextLine().trim()));
                        break;
                    case "3":
                        System.out.print("Nome do arquivo no servidor: ");
                        baixar(teclado.nextLine().trim());
                        break;
                    case "0":
                        out.writeUTF(Protocolo.CMD_SAIR);
                        out.flush();
                        return;
                    default:
                        System.out.println("Opção inválida.");
                }
            }
        } catch (IOException e) {
            System.err.println("Conexão perdida: " + e.getMessage());
        }
    }

    /**
     * Pede ao servidor a lista de arquivos e a imprime (nome e tamanho).
     * Lê: status, quantidade (int) e, para cada arquivo, nome (UTF) e tamanho (long).
     */
    void listar() throws IOException {
        out.writeUTF(Protocolo.CMD_LISTAR);
        out.flush();

        String erro = Protocolo.lerErro(in);
        if (erro != null) {
            System.out.println("Erro do servidor: " + erro);
            return;
        }
        int quantidade = in.readInt();
        if (quantidade == 0) {
            System.out.println("(nenhum arquivo no servidor)");
        }
        for (int i = 0; i < quantidade; i++) {
            String nome = in.readUTF();
            long tamanho = in.readLong();
            System.out.printf("  %-40s %12d bytes%n", nome, tamanho);
        }
    }

    /**
     * Envia um arquivo local ao servidor.
     *
     * <p>Primeiro manda o comando, o nome e o tamanho e espera o "OK" do servidor; só então
     * envia os bytes. Ao final, aguarda a confirmação de que o arquivo foi gravado.
     * Só o nome do arquivo é enviado (nunca o caminho local completo).
     *
     * @param arquivo caminho do arquivo no computador do cliente
     */
    void enviar(Path arquivo) throws IOException {
        if (!Files.isRegularFile(arquivo)) {
            System.out.println("Arquivo não encontrado: " + arquivo);
            return;
        }
        String nome = arquivo.getFileName().toString();
        if (!Protocolo.nomeValido(nome)) {
            System.out.println("Nome de arquivo não permitido (não pode começar com '.'): " + nome);
            return;
        }
        long tamanho = Files.size(arquivo);

        out.writeUTF(Protocolo.CMD_ENVIAR);
        out.writeUTF(nome);
        out.writeLong(tamanho);
        out.flush();

        String erro = Protocolo.lerErro(in); // servidor aceitou o nome e o tamanho?
        if (erro != null) {
            System.out.println("Erro do servidor: " + erro);
            return;
        }

        try (InputStream origem = Files.newInputStream(arquivo)) {
            Protocolo.copiar(origem, out, tamanho);
        }
        out.flush();

        erro = Protocolo.lerErro(in); // servidor terminou de gravar?
        if (erro != null) {
            System.out.println("Erro do servidor: " + erro);
        } else {
            System.out.println("Arquivo enviado: " + nome + " (" + tamanho + " bytes)");
        }
    }

    /**
     * Baixa um arquivo do servidor e o salva na pasta de downloads.
     *
     * <p>Os bytes são gravados em um arquivo temporário e só renomeados ao final, para que
     * uma queda de conexão no meio não deixe um arquivo incompleto com o nome final.
     * Se já existir um arquivo com o mesmo nome na pasta de downloads, ele é substituído.
     *
     * @param nome nome do arquivo, exatamente como aparece na listagem do servidor
     */
    void baixar(String nome) throws IOException {
        if (!Protocolo.nomeValido(nome)) {
            System.out.println("Nome de arquivo inválido.");
            return;
        }

        out.writeUTF(Protocolo.CMD_BAIXAR);
        out.writeUTF(nome);
        out.flush();

        String erro = Protocolo.lerErro(in);
        if (erro != null) {
            System.out.println("Erro do servidor: " + erro);
            return;
        }
        long tamanho = in.readLong();

        Path temporario = Files.createTempFile(pastaDownloads, ".download-", ".tmp");
        try {
            try (OutputStream destino = Files.newOutputStream(temporario)) {
                Protocolo.copiar(in, destino, tamanho);
            }
            Path finalPath = pastaDownloads.resolve(nome);
            Files.move(temporario, finalPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Arquivo salvo em " + finalPath.toAbsolutePath()
                    + " (" + tamanho + " bytes)");
        } catch (IOException e) {
            Files.deleteIfExists(temporario);
            throw e;
        }
    }
}
