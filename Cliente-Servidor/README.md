# SiCA — Sistema de Compartilhamento de Arquivos

Aplicação cliente/servidor em Java (somente biblioteca padrão, sockets TCP) que permite a um
cliente **enviar**, **listar** e **baixar** arquivos guardados em um servidor.

## Arquivos

| Arquivo | Papel |
|---|---|
| `Servidor.java` | Aceita conexões e atende os comandos dos clientes (uma thread por cliente). |
| `Cliente.java` | Menu de console que conversa com o servidor. |
| `Protocolo.java` | Constantes do protocolo e funções usadas pelos dois lados (validação de nome, cópia de bytes, status OK/ERRO). |

## Como executar

Requer Java 8 ou superior.

```bash
javac *.java

# Terminal 1 — servidor (porta e pasta são opcionais; padrões: 5000 e ./arquivos_servidor)
java Servidor [porta] [pasta]

# Terminal 2 — cliente (padrões: localhost, 5000 e ./downloads)
java Cliente [host] [porta] [pasta_de_downloads]
```

Menu do cliente:

```
1) Listar arquivos do servidor
2) Enviar arquivo        -> pede o caminho do arquivo local
3) Baixar arquivo        -> pede o nome (como aparece na listagem)
0) Sair
```

## Como funciona

1. O **servidor** cria um `ServerSocket` na porta escolhida e fica em `accept()`. A cada conexão,
   entrega o socket a uma thread (`AtendimentoCliente`), de modo que vários clientes são atendidos
   ao mesmo tempo.
2. O **cliente** abre um `Socket` para o servidor e mantém **uma única conexão durante toda a
   sessão**, enviando um comando por vez.
3. Toda mensagem usa `DataInputStream`/`DataOutputStream`: textos em UTF, inteiros e longos em
   formato fixo. O cliente envia um **comando**; o servidor responde com `OK` (mais os dados do
   comando) ou `ERRO` + motivo.

### Protocolo

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

### Decisões de projeto

- **Upload/download atômicos:** os bytes são recebidos em um arquivo temporário (`.upload-*.tmp` no
  servidor, `.download-*.tmp` no cliente) e só são renomeados para o nome final quando completos. Uma
  conexão que caia no meio não deixa arquivo pela metade nem aparece na listagem.
- **Segurança do nome do arquivo:** nomes com `/`, `\`, vazios ou iniciados por `.` são recusados
  (`Protocolo.nomeValido`). Isso impede pedidos como `../../etc/passwd` (*path traversal*) e esconde
  os temporários. O cliente só envia o nome do arquivo, nunca o caminho local.
- **Nome repetido:** enviar um arquivo com nome já existente no servidor o **substitui**; o mesmo vale
  para baixar um arquivo para uma pasta que já o contenha.
- **Erros:** erros do usuário (arquivo inexistente, nome inválido) são mostrados e o menu continua;
  erros de rede encerram a sessão com uma mensagem.

### Limitações (fora do escopo do exercício)

Não há autenticação nem criptografia (os dados trafegam em texto claro), limite de tamanho/cota de
disco, nem proteção contra dois uploads simultâneos do mesmo nome (vence o último a terminar).

## Testes realizados

Executado com um arquivo binário aleatório de 5 MB, um arquivo vazio e um nome com acentos e espaço:
os SHA-256 do original, da cópia no servidor e das cópias baixadas (inclusive por dois clientes
simultâneos) são idênticos. Também foram verificados: arquivo local inexistente, nome iniciado por `.`,
download de arquivo inexistente e nome com `../`, todos recusados sem derrubar a sessão.
