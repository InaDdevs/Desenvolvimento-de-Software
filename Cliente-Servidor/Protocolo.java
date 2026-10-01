import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Definição do protocolo do SiCA e utilitários usados tanto pelo cliente quanto pelo servidor.
 *
 * <h2>Visão geral do protocolo</h2>
 * A comunicação é feita sobre uma única conexão TCP, usando {@link DataInputStream} e
 * {@link DataOutputStream}. Esses fluxos garantem que textos (UTF), inteiros e longos
 * sejam escritos sempre no mesmo formato (big-endian), independente da máquina.
 *
 * <p>O cliente sempre inicia a conversa enviando um <b>comando</b> (texto). O servidor responde
 * com um <b>status</b>: {@code "OK"} ou {@code "ERRO"} seguido de uma mensagem. Em caso de
 * {@code OK}, vêm os dados específicos de cada comando:
 *
 * <pre>
 * LISTAR   C→S: "LISTAR"
 *          S→C: "OK", int quantidade, { UTF nome, long tamanho } * quantidade
 *
 * ENVIAR   C→S: "ENVIAR", UTF nome, long tamanho
 *          S→C: "OK"  (nome aceito; pode mandar os bytes)    ou "ERRO", UTF motivo
 *          C→S: tamanho bytes do arquivo
 *          S→C: "OK"  (arquivo gravado)
 *
 * BAIXAR   C→S: "BAIXAR", UTF nome
 *          S→C: "OK", long tamanho, tamanho bytes do arquivo    ou "ERRO", UTF motivo
 *
 * SAIR     C→S: "SAIR"   (o servidor encerra a conexão)
 * </pre>
 *
 * Como o tamanho do arquivo é sempre informado antes dos bytes, o receptor sabe exatamente
 * quando o arquivo termina, sem precisar fechar a conexão. Assim a mesma conexão serve
 * para vários comandos seguidos.
 */
public final class Protocolo {

    /** Porta TCP usada quando nenhuma é informada na linha de comando. */
    public static final int PORTA_PADRAO = 5000;

    /** Tamanho do bloco usado ao copiar arquivos de/para a rede (8 KiB). */
    public static final int TAMANHO_BUFFER = 8192;

    // Comandos enviados pelo cliente.
    public static final String CMD_LISTAR = "LISTAR";
    public static final String CMD_ENVIAR = "ENVIAR";
    public static final String CMD_BAIXAR = "BAIXAR";
    public static final String CMD_SAIR = "SAIR";

    // Status enviados pelo servidor.
    public static final String OK = "OK";
    public static final String ERRO = "ERRO";

    private Protocolo() {
        // Classe só com métodos estáticos: não deve ser instanciada.
    }

    /**
     * Verifica se um nome de arquivo é aceitável.
     *
     * <p>Esta validação é a defesa contra <i>path traversal</i>: sem ela, um cliente poderia
     * pedir o arquivo {@code ../../etc/passwd} ou gravar fora da pasta do servidor. Por isso
     * o nome deve ser apenas um nome simples, sem separadores de diretório. Nomes iniciados
     * por ponto também são recusados, pois o servidor usa arquivos {@code .upload-*.tmp}
     * (ocultos) para receber uploads em andamento.
     *
     * @param nome nome recebido pela rede ou digitado pelo usuário
     * @return {@code true} se for um nome simples e seguro
     */
    public static boolean nomeValido(String nome) {
        return nome != null
                && !nome.isEmpty()
                && nome.length() <= 255
                && !nome.startsWith(".")
                && nome.indexOf('/') < 0
                && nome.indexOf('\\') < 0
                && nome.indexOf('\0') < 0;
    }

    /**
     * Copia exatamente {@code bytes} bytes de {@code in} para {@code out}.
     *
     * <p>É o coração da transferência: um laço lê blocos de até {@link #TAMANHO_BUFFER} bytes e os
     * repassa, sem nunca ler além do tamanho combinado (para não "engolir" o próximo comando
     * que esteja na mesma conexão). Se a origem terminar antes da hora (conexão caiu, arquivo
     * truncado) lança {@link EOFException}.
     *
     * @param in    origem dos dados (socket ou arquivo)
     * @param out   destino dos dados (arquivo ou socket)
     * @param bytes quantidade exata de bytes a copiar
     * @throws IOException em falha de leitura/escrita ou se os dados acabarem antes do esperado
     */
    public static void copiar(InputStream in, OutputStream out, long bytes) throws IOException {
        byte[] buffer = new byte[TAMANHO_BUFFER];
        long restante = bytes;
        while (restante > 0) {
            int lidos = in.read(buffer, 0, (int) Math.min(buffer.length, restante));
            if (lidos < 0) {
                throw new EOFException("Conexão/arquivo terminou com " + restante + " bytes faltando");
            }
            out.write(buffer, 0, lidos);
            restante -= lidos;
        }
    }

    /** Envia o status {@code OK} e força o envio imediato (flush) dos bytes pendentes. */
    public static void enviarOk(DataOutputStream out) throws IOException {
        out.writeUTF(OK);
        out.flush();
    }

    /** Envia o status {@code ERRO} com um motivo legível e força o envio (flush). */
    public static void enviarErro(DataOutputStream out, String motivo) throws IOException {
        out.writeUTF(ERRO);
        out.writeUTF(motivo);
        out.flush();
    }

    /**
     * Lê o status enviado pelo outro lado.
     *
     * @return {@code null} se o status foi {@code OK}; caso contrário, a mensagem de erro
     */
    public static String lerErro(DataInputStream in) throws IOException {
        String status = in.readUTF();
        return OK.equals(status) ? null : in.readUTF();
    }
}
