# SiCA — Sistema de Compartilhamento de Arquivos

Aplicação cliente/servidor em Java (somente biblioteca padrão, sockets TCP) que permite
**enviar**, **listar** e **baixar** arquivos guardados em um servidor. Há dois clientes: um de
**console** e uma **interface web** no navegador.

```
 Cliente (console) ───────────────TCP───────────────┐
                                                     ▼
 Navegador ──HTTP──▶ ServidorWeb ──────TCP──────▶ Servidor ──▶ pasta de arquivos
```

## Arquivos (pasta `Cliente-Servidor/`)

| Arquivo | Papel |
|---|---|
| `Servidor.java` | Aceita conexões e atende os comandos dos clientes (uma thread por cliente). |
| `Cliente.java` | Cliente de console: menu que conversa com o servidor. |
| `ServidorWeb.java` | Interface web: serve a página e traduz requisições HTTP em comandos do protocolo TCP. |
| `web/index.html` | Página da interface web (HTML + JavaScript puro, sem dependências externas). |
| `Protocolo.java` | Constantes do protocolo e funções usadas por todos (validação de nome, cópia de bytes, status OK/ERRO). |

## Como executar

Requer Java 8 ou superior. Todos os comandos são executados dentro de `Cliente-Servidor/`.

```bash
cd Cliente-Servidor
javac *.java

# Terminal 1 — servidor (porta e pasta opcionais; padrões: 5000 e ./arquivos_servidor)
java Servidor [porta] [pasta]

# Terminal 2 — cliente de console (padrões: localhost, 5000 e ./downloads)
java Cliente [host] [porta] [pasta_de_downloads]

# Terminal 3 (opcional) — interface web; depois abra http://localhost:8080
java ServidorWeb [portaHttp] [hostSica] [portaSica]    # padrões: 8080, localhost e 5000
```

O `ServidorWeb` precisa ser iniciado de dentro de `Cliente-Servidor/` (ele lê `web/index.html`
da pasta atual) e com o `Servidor` já no ar. Ele pode apontar para um servidor SiCA em outra
máquina, passando `hostSica` e `portaSica`.

Menu do cliente de console:

```
1) Listar arquivos do servidor
2) Enviar arquivo        -> pede o caminho do arquivo local
3) Baixar arquivo        -> pede o nome (como aparece na listagem)
0) Sair
```

Interface web: a lista de arquivos aparece na tela, cada um com um botão **Baixar**; para enviar,
arraste arquivos para a área tracejada ou clique nela (vários de uma vez, com barra de progresso;
se o nome já existir, a página pergunta antes de substituir).

## Como funciona

1. O **servidor** cria um `ServerSocket` na porta escolhida e fica em `accept()`. A cada conexão,
   entrega o socket a uma thread (`AtendimentoCliente`), de modo que vários clientes são atendidos
   ao mesmo tempo.
2. O **cliente de console** abre um `Socket` para o servidor e mantém **uma única conexão durante
   toda a sessão**, enviando um comando por vez.
3. Toda mensagem usa `DataInputStream`/`DataOutputStream`: textos em UTF, inteiros e longos em
   formato fixo. O cliente envia um **comando**; o servidor responde com `OK` (mais os dados do
   comando) ou `ERRO` + motivo.

### Protocolo TCP

```
LISTAR   C→S: "LISTAR"
         S→C: "OK", int quantidade, { UTF nome, long tamanho } * quantidade

ENVIAR   C→S: "ENVIAR", UTF nome, long tamanho
         S→C: "OK"  (nome aceito)                      ou "ERRO", UTF motivo
         C→S: <tamanho> bytes do arquivo
         S→C: "OK"  (arquivo gravado)

BAIXAR   C→S: "BAIXAR", UTF nome
         S→C: "OK", long tamanho, <tamanho> bytes      ou "ERRO", UTF motivo

SAIR     C→S: "SAIR"   (servidor fecha a conexão)
```

O **tamanho vem antes dos bytes**. Assim quem recebe sabe exatamente onde o arquivo termina e a
mesma conexão pode ser reutilizada para o próximo comando. Os bytes são copiados em blocos de 8 KiB,
então arquivos grandes não precisam caber na memória, e qualquer tipo de arquivo (texto ou binário)
é transferido sem alteração.

No `ENVIAR`, o servidor responde `OK` *antes* de o cliente mandar os bytes: se o nome for inválido,
o erro é informado sem desperdiçar a transferência.

### Interface web

O `ServidorWeb` é uma **ponte**: não guarda nem processa arquivos por conta própria. Para cada
requisição HTTP do navegador, abre uma conexão TCP com o `Servidor` e fala o protocolo acima,
repassando os bytes direto de um lado ao outro (sem carregar o arquivo inteiro na memória). Assim
a regra de armazenamento fica em um só lugar e a interface web funciona com o mesmo servidor
que o cliente de console. Usa o servidor HTTP embutido no JDK (`com.sun.net.httpserver`).

| Requisição HTTP | Ação | Comando SiCA |
|---|---|---|
| `GET /` | página HTML | — |
| `GET /api/arquivos` | lista em JSON: `[{"nome":"...","tamanho":123}]` | `LISTAR` |
| `GET /api/arquivos/{nome}` | baixa o arquivo | `BAIXAR` |
| `PUT /api/arquivos/{nome}` | envia um arquivo (o corpo é o próprio arquivo) | `ENVIAR` |

Erros voltam com o status HTTP adequado e o corpo `{"erro":"mensagem"}`: `400` nome inválido,
`404` arquivo inexistente, `411` envio sem `Content-Length`, `405` método não permitido e
`502` servidor SiCA fora do ar. O envio usa `PUT` (e não `POST`) para que outro site aberto no
navegador não consiga disparar uploads para cá sem permissão (CORS).

### Decisões de projeto

- **Upload/download atômicos:** os bytes são recebidos em um arquivo temporário (`.upload-*.tmp` no
  servidor, `.download-*.tmp` no cliente de console) e só são renomeados para o nome final quando
  completos. Uma conexão que caia no meio não deixa arquivo pela metade nem aparece na listagem.
- **Segurança do nome do arquivo:** nomes com `/`, `\`, vazios ou iniciados por `.` são recusados
  (`Protocolo.nomeValido`), tanto pelo servidor quanto pelos clientes. Isso impede pedidos como
  `../../etc/passwd` (*path traversal*) e esconde os temporários. O cliente só envia o nome do
  arquivo, nunca o caminho local.
- **Nome repetido:** enviar um arquivo com nome já existente no servidor o **substitui**; o mesmo vale
  para baixar um arquivo para uma pasta que já o contenha.
- **Erros:** erros do usuário (arquivo inexistente, nome inválido) são mostrados e o menu continua;
  erros de rede encerram a sessão com uma mensagem.
- **Página web:** os nomes dos arquivos são inseridos na página como texto (`textContent`), nunca como
  HTML, então um arquivo chamado `"><img onerror=...>` não executa código no navegador de ninguém.

## Testes realizados

**Servidor + cliente de console:** arquivo binário aleatório de 5 MB, arquivo vazio e nome com acentos
e espaço. Os SHA-256 do original, da cópia no servidor e das cópias baixadas (inclusive por dois
clientes simultâneos) são idênticos. Também foram recusados, sem derrubar a sessão: arquivo local
inexistente, nome iniciado por `.`, download de arquivo inexistente e, enviados direto ao servidor
por um cliente "malicioso", nome com `../`, tamanho negativo e comando desconhecido.

**Interface web** (via `curl`): os mesmos três arquivos enviados por `PUT` e baixados por `GET` com
hash idêntico, incluindo dois downloads simultâneos de 5 MB; listagem em JSON válida mesmo com
aspas e `<` no nome; respostas `400` (nome com `../`, `\` ou `.`), `404`, `405`, `411` e `502`
(servidor SiCA parado); upload de 5 MB recusado por nome inválido devolve o erro em vez de derrubar
a conexão; nenhum arquivo temporário sobra na pasta do servidor.
