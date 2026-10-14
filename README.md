# Sylph UNOPAR

A **Sylph** é uma tutora virtual desenvolvida como protótipo acadêmico para responder dúvidas sobre o Estágio Obrigatório da UNOPAR.

Utiliza **Java 21, Spring Boot, Ollama e arquitetura RAG**, consultando o Manual de Estágio para gerar respostas fundamentadas, com indicação das páginas utilizadas.

A aplicação funciona exclusivamente pelo terminal, sem interface gráfica ou servidor web próprio.

> Projeto acadêmico independente, não homologado oficialmente pela UNOPAR.

## Tecnologias

- Java 21 e Spring Boot
- Apache Maven
- Ollama com modelo Qwen
- Embeddings `nomic-embed-text`
- Apache PDFBox
- Chunking recursivo e semântico
- Recuperação híbrida com similaridade cosseno e BM25
- Auditoria e validação de citações

## Requisitos

Antes de executar, instale:

- [Java JDK 21](https://adoptium.net/)
- [Apache Maven](https://maven.apache.org/)
- [Ollama](https://ollama.com/download)

## Instalação

**1. Configure o Ollama**

Verifique qual modelo base está indicado no `Modelfile` e instale-o. Para a configuração com Qwen 2.5 7B:

```powershell
ollama pull qwen3:8b
ollama pull nomic-embed-text
ollama create sylph-unopar -f Modelfile
```

O Ollama deve estar em execução. Se necessário, utilize `ollama serve` em outro terminal.

**2. Configure o projeto**

Na pasta principal, crie ou ajuste o `config.json` com as configurações dos modelos e da conexão com o Ollama. Caso esteja disponível o `config.example.json`, copie-o para `config.json`.

Os materiais do RAG devem estar em `dados/`:

```text
dados/
├── Manual Estagio Obrigatorio UNOPAR 2026-2.pdf
├── base_recursivo.csv
└── base_semantico.csv
```

**3. Compile a aplicação**

```powershell
mvn clean package
```

**4. Inicie a Sylph**

```powershell
java -jar target/sylph-unopar-java-1.0.0.jar
```

## Como utilizar

Na versão com seleção interativa, escolha o método de chunking:

```text
SYLPH UNOPAR

Selecione o método de chunking:

[1] Chunking Recursivo
[2] Chunking Semântico
[0] Sair

Escolha uma opção:
```

Após selecionar o método, digite suas perguntas normalmente.

**Comandos disponíveis:**

| Comando  | Função                         |
| -------- | ------------------------------ |
| `metodo` | Alterar o método de chunking   |
| `ajuda`  | Exibir os comandos disponíveis |
| `sair`   | Encerrar a Sylph               |

Exemplo de pergunta:

> Qual é o prazo para análise do cadastro de uma empresa?

A Sylph consulta a base documental e apresenta a resposta com suas fontes.

Saudações e agradecimentos simples são respondidos automaticamente, sem necessidade de consultar o RAG.

## Testes automatizados

Para executar os testes Java:

```powershell
mvn test
```

## Observações

- O Ollama e os modelos devem estar instalados no computador do usuário.
- Os documentos e embeddings são processados localmente.
- O comando `preparar` pode recriar as bases CSV, substituindo as existentes.
- As respostas são geradas por IA e devem ser conferidas com os documentos oficiais.
- A publicação do Manual de Estágio e das bases derivadas exige autorização de redistribuição quando aplicável.

## Finalidade acadêmica

A Sylph foi desenvolvida para estudar a aplicação de modelos de linguagem locais e técnicas de recuperação aumentada por geração (RAG) no apoio a estudantes do ensino superior.
