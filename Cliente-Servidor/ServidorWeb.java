import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Interface web do SiCA.
 *
 * <p>Este programa é uma <b>ponte (gateway)</b> entre o navegador e o {@link Servidor}:
 *
 * <pre>
 *   navegador  --HTTP-->  ServidorWeb  --TCP (protocolo SiCA)-->  Servidor
 * </pre>
 *
 * Ele serve a página {@code web/index.html} e expõe uma pequena API HTTP. Para cada requisição
 * da API, abre uma conexão TCP com o servidor SiCA e fala o mesmo protocolo do {@link Cliente}
 * (ver {@link Protocolo}). Assim toda a lógica de armazenamento continua em um só lugar, e a
 * interface web funciona também com um servidor SiCA em outra máquina.
 *
 * <h2>API HTTP</h2>
 * <pre>
 * GET /                      página HTML
 * GET /api/arquivos          lista em JSON: [{"nome": "...", "tamanho": 123}, ...]
 * GET /api/arquivos/{nome}   baixa o arquivo (resposta com o conteúdo binário)
 * PUT /api/arquivos/{nome}   envia um arquivo (o corpo da requisição são os bytes do arquivo)
 * </pre>
 * Erros são respondidos com status HTTP adequado e corpo {@code {"erro": "mensagem"}}.
 * O upload usa PUT (e não POST) de propósito: outro site aberto no navegador não consegue
 * disparar um PUT para cá sem permissão (CORS), o que evitaria envios não solicitados.
 *
 * <p>Uso: {@code java ServidorWeb [portaHttp] [hostSica] [portaSica]}
 * (padrões: 8090, localhost e 5000). Deve ser executado a partir da pasta do projeto, para que
 * {@code web/index.html} seja encontrado.
 */
public class ServidorWeb {

    /** Porta HTTP padrão. Evita a 8080 de propósito, que é muito usada por outros programas. */
    private static final int PORTA_HTTP_PADRAO = 8090;
    private static final String RAIZ_API = "/api/arquivos";
    private static final String JSON = "application/json; charset=utf-8";

    private final String hostSica;
    private final int portaSica;
    private final byte[] pagina; // conteúdo de web/index.html, lido uma vez na partida

    ServidorWeb(String hostSica, int portaSica) throws IOException {
        this.hostSica = hostSica;
        this.portaSica = portaSica;
        this.pagina = carregarPagina();
    }

    public static void main(String[] args) throws IOException {
        int portaHttp = args.length > 0 ? Integer.parseInt(args[0]) : PORTA_HTTP_PADRAO;
        String hostSica = args.length > 1 ? args[1] : "localhost";
        int portaSica = args.length > 2 ? Integer.parseInt(args[2]) : Protocolo.PORTA_PADRAO;

        ServidorWeb web = new ServidorWeb(hostSica, portaSica);
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(portaHttp), 0);
        } catch (BindException e) {
            System.err.println("A porta " + portaHttp + " já está em uso por outro programa. "
                    + "Escolha outra, por exemplo: java ServidorWeb " + (portaHttp + 1));
            System.exit(1);
            return; // o compilador não sabe que exit() não retorna; sem isso 'http' pareceria não atribuída
        }
        http.createContext("/", web::paginaInicial);
        http.createContext(RAIZ_API, web::api);
        // Sem isto o servidor HTTP usa uma única thread, e um upload lento travaria todos os outros.
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();
        System.out.println("SiCA web em http://localhost:" + portaHttp
                + "  (servidor SiCA: " + hostSica + ":" + portaSica + ")");
    }

    /** Lê {@code web/index.html} do classpath (a pasta atual, por padrão). */
    private static byte[] carregarPagina() throws IOException {
        try (InputStream in = ServidorWeb.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                throw new FileNotFoundException(
                        "web/index.html não encontrado: execute a partir da pasta do projeto");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[Protocolo.TAMANHO_BUFFER];
            int lidos;
            while ((lidos = in.read(buffer)) > 0) {
                bytes.write(buffer, 0, lidos);
            }
            return bytes.toByteArray();
        }
    }

    /** {@code GET /}: devolve a página; qualquer outro caminho fora da API é 404. */
    private void paginaInicial(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestURI().getPath().equals("/")) {
                responderErro(ex, 404, "Não encontrado");
            } else if (!ex.getRequestMethod().equals("GET")) {
                responderErro(ex, 405, "Método não permitido");
            } else {
                responder(ex, 200, "text/html; charset=utf-8", pagina);
            }
        } finally {
            ex.close();
        }
    }

    /**
     * Ponto de entrada de todas as requisições da API. Encaminha para {@link #rotear} e
     * traduz falhas de comunicação com o servidor SiCA em respostas HTTP 502 (Bad Gateway).
     */
    private void api(HttpExchange ex) throws IOException {
        try {
            rotear(ex);
        } catch (ConnectException e) {
            tentarResponderErro(ex, 502,
                    "Servidor SiCA indisponível em " + hostSica + ":" + portaSica);
        } catch (IOException e) {
            System.err.println("Falha em " + ex.getRequestMethod() + " " + ex.getRequestURI()
                    + ": " + e);
            tentarResponderErro(ex, 502, "Falha na comunicação com o servidor SiCA");
        } finally {
            ex.close();
        }
    }

    /** Escolhe a operação a partir do método HTTP e do caminho. */
    private void rotear(HttpExchange ex) throws IOException {
        String metodo = ex.getRequestMethod();
        String caminho = ex.getRequestURI().getPath(); // já decodificado (%C3%A7 -> ç)

        if (caminho.equals(RAIZ_API) || caminho.equals(RAIZ_API + "/")) {
            if (metodo.equals("GET")) {
                listar(ex);
            } else {
                metodoNaoPermitido(ex, "GET");
            }
        } else if (caminho.startsWith(RAIZ_API + "/")) {
            String nome = caminho.substring(RAIZ_API.length() + 1);
            if (metodo.equals("GET")) {
                baixar(ex, nome);
            } else if (metodo.equals("PUT")) {
                enviar(ex, nome);
            } else {
                metodoNaoPermitido(ex, "GET, PUT");
            }
        } else {
            responderErro(ex, 404, "Não encontrado");
        }
    }

    /**
     * {@code GET /api/arquivos}: executa o comando LISTAR no servidor SiCA e converte
     * a resposta (nome + tamanho de cada arquivo) para JSON.
     */
    private void listar(HttpExchange ex) throws IOException {
        try (Sessao s = new Sessao(hostSica, portaSica)) {
            s.out.writeUTF(Protocolo.CMD_LISTAR);
            s.out.flush();

            String erro = Protocolo.lerErro(s.in);
            if (erro != null) {
                responderErro(ex, 502, erro);
                return;
            }
            int quantidade = s.in.readInt();
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < quantidade; i++) {
                String nome = s.in.readUTF();
                long tamanho = s.in.readLong();
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"nome\":").append(textoJson(nome))
                    .append(",\"tamanho\":").append(tamanho).append('}');
            }
            json.append(']');
            responder(ex, 200, JSON, json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * {@code GET /api/arquivos/{nome}}: executa BAIXAR no servidor SiCA e repassa os bytes
     * ao navegador, <i>sem guardar o arquivo na memória</i>, pois os bytes vão direto do
     * socket TCP para a resposta HTTP. O tamanho vira o cabeçalho Content-Length, o que
     * permite ao navegador mostrar o progresso. O cabeçalho Content-Disposition faz o
     * navegador salvar o arquivo em vez de tentar exibi-lo.
     */
    private void baixar(HttpExchange ex, String nome) throws IOException {
        if (!Protocolo.nomeValido(nome)) {
            responderErro(ex, 400, "Nome de arquivo inválido");
            return;
        }
        try (Sessao s = new Sessao(hostSica, portaSica)) {
            s.out.writeUTF(Protocolo.CMD_BAIXAR);
            s.out.writeUTF(nome);
            s.out.flush();

            // Depois da validação do nome, o único erro possível do servidor é arquivo inexistente.
            String erro = Protocolo.lerErro(s.in);
            if (erro != null) {
                responderErro(ex, 404, erro);
                return;
            }
            long tamanho = s.in.readLong();

            String nomeUrl = URLEncoder.encode(nome, "UTF-8").replace("+", "%20");
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename*=UTF-8''" + nomeUrl);
            // Atenção: no HttpServer, tamanho 0 significa "chunked"; "sem corpo" é -1.
            ex.sendResponseHeaders(200, tamanho == 0 ? -1 : tamanho);
            if (tamanho > 0) {
                Protocolo.copiar(s.in, ex.getResponseBody(), tamanho);
            }
        }
    }

    /**
     * {@code PUT /api/arquivos/{nome}}: executa ENVIAR no servidor SiCA, repassando o corpo
     * da requisição HTTP (os bytes do arquivo) direto para o socket TCP.
     *
     * <p>É preciso o cabeçalho Content-Length, pois o protocolo SiCA informa o tamanho antes
     * dos bytes (navegadores o enviam automaticamente ao enviar um arquivo).
     * Respostas: 201 em caso de sucesso, 400 se o nome/tamanho forem recusados, 411 se faltar
     * o Content-Length.
     */
    private void enviar(HttpExchange ex, String nome) throws IOException {
        long tamanho = lerContentLength(ex);

        if (!Protocolo.nomeValido(nome)) {
            recusarUpload(ex, 400,
                    "Nome de arquivo inválido (não pode ter '/' ou '\\' nem começar com '.')");
            return;
        }
        if (tamanho < 0) {
            recusarUpload(ex, 411, "Cabeçalho Content-Length é obrigatório");
            return;
        }

        try (Sessao s = new Sessao(hostSica, portaSica)) {
            s.out.writeUTF(Protocolo.CMD_ENVIAR);
            s.out.writeUTF(nome);
            s.out.writeLong(tamanho);
            s.out.flush();

            String erro = Protocolo.lerErro(s.in); // o servidor aceitou o nome?
            if (erro != null) {
                recusarUpload(ex, 400, erro);
                return;
            }

            Protocolo.copiar(ex.getRequestBody(), s.out, tamanho);
            s.out.flush();

            erro = Protocolo.lerErro(s.in); // o servidor terminou de gravar?
            if (erro != null) {
                responderErro(ex, 502, erro);
                return;
            }
            responder(ex, 201, JSON, ("{\"nome\":" + textoJson(nome)
                    + ",\"tamanho\":" + tamanho + "}").getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Recusa um upload. O corpo da requisição ainda não foi lido, então é descartado antes de
     * responder: se o navegador ainda estiver enviando e a conexão for fechada, ele mostraria
     * "falha de rede" em vez da mensagem de erro.
     */
    private void recusarUpload(HttpExchange ex, int status, String mensagem) throws IOException {
        InputStream corpo = ex.getRequestBody();
        byte[] lixo = new byte[Protocolo.TAMANHO_BUFFER];
        while (corpo.read(lixo) >= 0) {
            // só consome
        }
        responderErro(ex, status, mensagem);
    }

    /** Devolve o valor do cabeçalho Content-Length, ou -1 se ausente/inválido. */
    private static long lerContentLength(HttpExchange ex) {
        String valor = ex.getRequestHeaders().getFirst("Content-Length");
        try {
            return valor == null ? -1 : Long.parseLong(valor.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void metodoNaoPermitido(HttpExchange ex, String permitidos) throws IOException {
        ex.getResponseHeaders().set("Allow", permitidos);
        responderErro(ex, 405, "Método não permitido");
    }

    /** Envia uma resposta completa (status, tipo e corpo já montado em memória). */
    private static void responder(HttpExchange ex, int status, String tipo, byte[] corpo)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, corpo.length == 0 ? -1 : corpo.length);
        if (corpo.length > 0) {
            OutputStream out = ex.getResponseBody();
            out.write(corpo);
        }
    }

    /** Envia uma resposta de erro no formato {@code {"erro": "mensagem"}}. */
    private static void responderErro(HttpExchange ex, int status, String mensagem)
            throws IOException {
        responder(ex, status, JSON,
                ("{\"erro\":" + textoJson(mensagem) + "}").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Tenta enviar um erro; se a resposta já tiver começado (por exemplo, a conexão caiu no
     * meio de um download), não há mais o que fazer além de deixar a conexão ser fechada.
     */
    private static void tentarResponderErro(HttpExchange ex, int status, String mensagem) {
        try {
            responderErro(ex, status, mensagem);
        } catch (IOException | IllegalStateException jaIniciada) {
            // ignorado de propósito
        }
    }

    /** Converte um texto em literal JSON (com aspas), escapando os caracteres especiais. */
    private static String textoJson(String texto) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : texto.toCharArray()) {
            switch (c) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                default:
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
            }
        }
        return json.append('"').toString();
    }

    /**
     * Uma conexão TCP com o servidor SiCA, com os fluxos de dados já preparados.
     *
     * <p>Ao fechar, apenas fecha o socket. Não envia SAIR: se a requisição HTTP for interrompida
     * no meio de um upload, os bytes de um SAIR seriam confundidos com dados do arquivo.
     * O servidor SiCA trata a conexão fechada como fim normal da sessão.
     */
    private static final class Sessao implements Closeable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Sessao(String host, int porta) throws IOException {
            socket = new Socket(host, porta);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
