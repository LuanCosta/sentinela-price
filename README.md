# Mercado Livre Price Tracker

Backend MVP em Kotlin/Ktor que percorre as páginas da busca oficial do Mercado Livre, mantém o anúncio pelo ID oficial, registra cada preço observado e cria uma promoção apenas quando surge uma nova mínima histórica.

## Arquitetura

O fluxo é propositalmente simples: **rota → serviço → cliente/repositório → PostgreSQL**. A camada `domain` contém a regra; `data` contém integrações; `application` monta e expõe a aplicação.

### Classes e arquivos

- `Application.kt`: ponto de composição. Carrega configurações, abre o pool, cria o cliente HTTP, repositórios e serviço, e instala os plugins Ktor. É o único lugar que conhece todas as implementações.
- `AppConfig`: lê e valida variáveis de ambiente. Impede que credenciais apareçam no código.
- `Database.kt`: configura o pool HikariCP e entrega uma conexão Exposed com PostgreSQL.
- `Serialization.kt`: configura Kotlin Serialization e tolera campos externos desconhecidos.
- `StatusPages.kt`: converte exceções de domínio em erros HTTP padronizados, sem vazar detalhes internos.
- `Routing.kt`: declara as rotas, valida paginação e o `X-Admin-Secret`. Não contém regra de preço.
- `Models.kt`: modelos internos e DTOs de resposta. `MarketItem` é a representação interna desacoplada do JSON do Mercado Livre. Valores monetários usam `BigDecimal`; respostas usam strings decimais para preservar exatidão no JSON.
- `ProductTable`: mapeia anúncios atuais e garante unicidade de `external_item_id`.
- `PriceHistoryTable`: mapeia observações; a chave única produto/execução torna uma repetição segura.
- `PromotionTable`: guarda recordes históricos sem apagar os anteriores e bloqueia duplicatas idênticas.
- `SyncExecutionTable`: registra diagnóstico, contadores e estado de cada execução.
- `MercadoLivreClient`: única classe que fala HTTP com o Mercado Livre. Busca produtos em `/products/search`, percorre os anúncios associados em `/products/{id}/items` e obtém o preço atual em `/items/{id}/sale_price`. Pagina, deduplica IDs, aplica timeout e repete apenas falhas temporárias/429.
- `HighlightRotationRepository`: guarda o cursor da descoberta automática. Cada sincronização sem corpo avança para o próximo lote de categorias, inclusive após reiniciar a aplicação.
- `MercadoLivreTokenService`: fornece o Bearer token ao cliente. Reaproveita o token enquanto estiver válido e, cinco minutos antes do vencimento, troca o `refresh_token` por um novo par de tokens. Um `Mutex` impede duas renovações simultâneas dentro da aplicação.
- `MercadoLivreTokenRepository`: mantém no PostgreSQL a única credencial OAuth corrente. Isso é necessário porque o Mercado Livre gira o `refresh_token`: depois da troca, somente o novo valor deve ser usado.
- `MarketClient`: pequeno contrato que desacopla o serviço do cliente real e torna os testes previsíveis.
- `ProductRepository`: concentra transações de produto e consultas paginadas. Em `process`, bloqueia a linha, lê a mínima **antes** da nova observação, faz upsert, grava histórico e eventualmente promoção na mesma transação.
- `SyncRepository`: abre/finaliza/lista execuções. Um `Mutex` protege a instância e o índice parcial do PostgreSQL protege múltiplas instâncias.
- `ProductSyncService`: orquestra termos, cliente e transações, acumula métricas e decide entre `SUCCESS`, `PARTIAL_SUCCESS` e `FAILED`.
- Exceções em `Repositories.kt`: representam falhas esperadas (`404`, `409`, `502` etc.) sem acoplar o domínio ao Ktor.

Os arquivos de teste usam banco H2 em modo PostgreSQL apenas para isolamento rápido. `ProductRepositoryTest` cobre a sequência 100 → 90 → 95 → 92 → 85, identidade externa e decimal exato; `MercadoLivreClientTest` cobre paginação e indisponibilidade; `MercadoLivreTokenServiceTest` cobre renovação, rotação e proteção dos segredos; `RoutingTest` cobre autenticação e conflito.

## Regra da mínima histórica

Dentro de uma transação por item:

1. Busca e bloqueia o produto por `external_item_id`.
2. Consulta `min(price)` no histórico já confirmado.
3. Insere ou atualiza o produto.
4. Insere a observação da execução.
5. Se havia mínima anterior e `preço atual < mínima anterior`, grava uma promoção.

Assim, a primeira observação não promove e uma queda contra apenas o preço anterior não é suficiente.

## Banco Supabase

1. Crie um projeto no Supabase.
2. Execute [`sql/001_initial_schema.sql`](sql/001_initial_schema.sql) no SQL Editor.
3. Copie `.env.example` para `.env` e preencha a JDBC URL, usuário e senha. Use `sslmode=require`. O `.env` é carregado automaticamente na execução local e está ignorado pelo Git.

Se o banco já foi criado com versões anteriores, execute as migrações ainda não aplicadas em ordem. Para a descoberta automática por categorias, execute [`sql/003_highlight_rotation.sql`](sql/003_highlight_rotation.sql).

O índice parcial `only_one_running_sync` é importante: ele torna impossível haver duas linhas `RUNNING`, inclusive se duas máquinas Fly receberem chamadas ao mesmo tempo.

## Execução local

Requisitos: JDK 21 e PostgreSQL/Supabase configurado. O Gradle Wrapper já está incluído.

```bash
./gradlew test
./gradlew run
```

Ou com Docker:

```bash
docker build -t mercado-livre-price-tracker .
docker run --env-file .env -p 8080:8080 mercado-livre-price-tracker
```

## Variáveis

| Nome | Obrigatória | Uso |
|---|---:|---|
| `DB_URL` | sim | URL JDBC PostgreSQL |
| `DB_USER` | sim | usuário do banco |
| `DB_PASSWORD` | sim | senha do banco |
| `MERCADO_LIVRE_API_URL` | não | padrão `https://api.mercadolibre.com` |
| `MERCADO_LIVRE_CLIENT_ID` | sim | identificador da aplicação criada no Mercado Livre Developers |
| `MERCADO_LIVRE_CLIENT_SECRET` | sim | segredo da aplicação; nunca deve ser enviado ao frontend |
| `MERCADO_LIVRE_REFRESH_TOKEN` | sim | token inicial obtido no OAuth; usado apenas quando ainda não há credencial no banco |
| `SYNC_ADMIN_SECRET` | sim | protege rotas administrativas |
| `SEARCH_TERMS` | não | termos padrão separados por vírgula |
| `HIGHLIGHT_BATCH_SIZE` | não | categorias processadas por sincronização automática; padrão `3`, máximo `10` |
| `HIGHLIGHT_CATEGORY_IDS` | não | categorias folha separadas por vírgula; vazio usa uma seleção comercial variada |
| `TRACKED_PRODUCTS_BATCH_SIZE` | não | anúncios já salvos revisados em cada execução automática; padrão `50`, máximo `500` |

## Testando as rotas

Importe `postman_collection.json` no Postman e configure `baseUrl` e `adminSecret`, ou use os exemplos:

```bash
curl http://localhost:8080/health
curl "http://localhost:8080/api/products?page=1&limit=20&search=notebook"
curl "http://localhost:8080/api/products/1/history?page=1&limit=50"
curl "http://localhost:8080/api/promotions?page=1&limit=20&active=true"
curl "http://localhost:8080/api/promotions/urls"
curl -X POST http://localhost:8080/api/admin/affiliate-links -H "X-Admin-Secret: seu-segredo" -H "Content-Type: application/json" -d '{"entries":[{"originalUrl":"https://produto.mercadolivre.com.br/...","affiliateUrl":"https://meli.la/..."}]}'
curl http://localhost:8080/api/promotions/1
curl -X POST http://localhost:8080/api/admin/sync -H "X-Admin-Secret: seu-segredo" -H "Content-Type: application/json" -d '{"searchTerms":["notebook","placa de vídeo"]}'
curl "http://localhost:8080/api/admin/sync/executions?page=1&limit=20" -H "X-Admin-Secret: seu-segredo"
```

Um `POST /api/admin/sync` sem corpo executa a descoberta automática: seleciona o próximo lote de categorias, consulta os 20 mais vendidos de cada uma, processa os termos opcionais de `SEARCH_TERMS` e revisita um lote de anúncios já salvos. Promoções ativas têm prioridade; as demais vagas são preenchidas pelos produtos com `last_seen_at` mais antigo, criando uma rotação natural. Um corpo com `searchTerms` executa apenas a busca manual solicitada. Não há rota adicional.

A rota responde imediatamente com HTTP `202 Accepted` e a execução em estado `RUNNING`. O processamento continua em segundo plano e pode ser acompanhado em `GET /api/admin/sync/executions`. Isso evita manter clientes como Postman ou GPT Actions aguardando durante toda a consulta ao Mercado Livre.

O arquivo `openapi-gpt-action.yaml` contém o schema para configurar uma Action em um GPT personalizado. O texto de `gpt-instructions.md` pode ser usado no campo de instruções do GPT.

Os rankings podem trazer `ITEM`, `PRODUCT` ou `USER_PRODUCT`. O cliente resolve os três formatos oficiais; para cada produto de catálogo consulta no máximo as três ofertas públicas mais baratas da primeira página, mantendo a execução limitada. Quando o Mercado Livre informa preço original maior que o atual, a promoção é criada já na primeira observação; a mínima histórica continua funcionando em paralelo.

Na listagem com `active=true`, anúncios que pertencem ao mesmo `catalogProductId` são agrupados. A API retorna a oferta de menor preço (maior desconto no desempate) e informa em `offersCount` quantos anúncios ativos foram comparados. Anúncios sem identificador de catálogo permanecem separados. Se o preço de um anúncio deixar o valor promocional, sua promoção anterior passa para `active=false`; o histórico não é apagado.

`GET /api/promotions/urls` exporta somente os links das melhores promoções ativas, um por linha, prontos para colar no Gerador de Produtos Recomendados. Para integrações que prefiram vírgulas, use `?format=csv`. A rota elimina URLs repetidas e retorna `text/plain`.

`POST /api/admin/affiliate-links` importa pares de URL original e link curto gerado oficialmente. A rota exige `X-Admin-Secret`, aceita apenas HTTPS no domínio `meli.la`, associa o link ao produto e faz a listagem de promoções retornar o campo `affiliateUrl`. Execute `sql/004_affiliate_links.sql` antes de utilizá-la em um banco existente.

Para o fluxo manual simplificado, use `POST /api/admin/affiliate-links/ordered` com `Content-Type: text/plain` e cole somente os links `meli.la`, um por linha. O backend associa pela mesma ordem de `GET /api/promotions/urls` e rejeita a operação se as quantidades forem diferentes. Não execute uma sincronização entre exportar e importar.

## Renovação OAuth automática

Na primeira consulta, o backend usa `MERCADO_LIVRE_REFRESH_TOKEN` do `.env`, solicita um novo `access_token` e salva no banco tanto o acesso quanto o **novo** `refresh_token`. Depois disso, o banco é a fonte de verdade e a renovação ocorre automaticamente antes do vencimento; não é necessário copiar tokens manualmente.

O `refresh_token` é de uso único. Portanto, não substitua o valor salvo no banco por um token antigo. Se o Mercado Livre revogar ou expirar a autorização, refaça o OAuth, atualize `MERCADO_LIVRE_REFRESH_TOKEN` e execute `delete from mercado_livre_credentials where id = 1;` uma única vez antes de reiniciar o backend.

## Deploy no Fly.io

Altere o nome globalmente único em `fly.toml`, autentique-se e configure segredos:

```bash
fly launch --no-deploy
fly secrets set DB_URL="..." DB_USER="..." DB_PASSWORD="..." MERCADO_LIVRE_CLIENT_ID="..." MERCADO_LIVRE_CLIENT_SECRET="..." MERCADO_LIVRE_REFRESH_TOKEN="..." SYNC_ADMIN_SECRET="..." SEARCH_TERMS="notebook,placa de vídeo"
fly deploy
```

Nunca grave `.env`, `client_secret`, `access_token` ou `refresh_token` no Git. A tabela `mercado_livre_credentials` contém segredos e não deve ser exposta por endpoints públicos.

## Limites conscientes do MVP

- Cada anúncio associado ao produto de catálogo é tratado como item distinto pelo ID oficial.
- A descoberta por palavra-chave cobre produtos ativos do catálogo; anúncios fora do catálogo não são expostos por esse fluxo oficial.
- O cliente percorre o catálogo até o limite de offset configurado (1.000 por padrão) e pagina todos os anúncios associados retornados.
- Produtos ausentes numa execução não são removidos.
- Sincronização é manual; um agendador externo pode chamar a rota depois, sem alterar a regra central.
- Para simplicidade, não há ORM de entidades nem framework de injeção de dependência.
