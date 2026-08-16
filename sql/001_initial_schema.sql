create table if not exists products (
    id bigserial primary key,
    external_item_id varchar(64) not null unique,
    catalog_product_id varchar(64),
    title varchar(512) not null,
    permalink text,
    thumbnail_url text,
    seller_id bigint,
    currency varchar(8) not null,
    current_price numeric(19,2) not null check (current_price >= 0),
    original_price numeric(19,2),
    available_quantity integer,
    condition varchar(32),
    status varchar(32),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);

create table if not exists sync_executions (
    id bigserial primary key,
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    status varchar(32) not null check (status in ('RUNNING','SUCCESS','PARTIAL_SUCCESS','FAILED')),
    search_term text not null,
    items_received integer not null default 0,
    products_created integer not null default 0,
    products_updated integer not null default 0,
    history_created integer not null default 0,
    promotions_created integer not null default 0,
    error_message text
);

create unique index if not exists only_one_running_sync
    on sync_executions ((status)) where status = 'RUNNING';

create table if not exists price_history (
    id bigserial primary key,
    product_id bigint not null references products(id),
    price numeric(19,2) not null check (price >= 0),
    original_price numeric(19,2),
    observed_at timestamptz not null default now(),
    sync_execution_id bigint not null references sync_executions(id),
    unique (product_id, sync_execution_id)
);
create index if not exists idx_price_history_product on price_history(product_id);
create index if not exists idx_price_history_observed on price_history(observed_at desc);
create index if not exists idx_price_history_product_observed on price_history(product_id, observed_at desc);

create table if not exists promotions (
    id bigserial primary key,
    product_id bigint not null references products(id),
    previous_lowest_price numeric(19,2) not null,
    promotional_price numeric(19,2) not null,
    discount_amount numeric(19,2) not null,
    discount_percent numeric(7,2) not null,
    detected_at timestamptz not null default now(),
    sync_execution_id bigint not null references sync_executions(id),
    active boolean not null default true,
    check (promotional_price < previous_lowest_price),
    unique (product_id, promotional_price, sync_execution_id)
);
create index if not exists idx_promotions_detected on promotions(detected_at desc);
create index if not exists idx_promotions_active_detected on promotions(active, detected_at desc);

create table if not exists mercado_livre_credentials (
    id integer primary key check (id = 1),
    access_token text not null,
    refresh_token text not null,
    expires_at timestamptz not null,
    updated_at timestamptz not null default now()
);

comment on table mercado_livre_credentials is
    'Estado OAuth rotativo do Mercado Livre. Restringir acesso e nunca expor pela API.';

create table if not exists highlight_rotation (
    id integer primary key check (id = 1),
    next_index integer not null default 0 check (next_index >= 0),
    updated_at timestamptz not null default now()
);

create table if not exists affiliate_links (
    id bigserial primary key,
    product_id bigint not null unique references products(id) on delete cascade,
    original_url text not null,
    affiliate_url text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
