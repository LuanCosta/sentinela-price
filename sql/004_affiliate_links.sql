create table if not exists affiliate_links (
    id bigserial primary key,
    product_id bigint not null unique references products(id) on delete cascade,
    original_url text not null,
    affiliate_url text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

comment on table affiliate_links is
    'Associação entre anúncio monitorado e link curto gerado oficialmente no Portal de Afiliados.';

alter table affiliate_links enable row level security;
