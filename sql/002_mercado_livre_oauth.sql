-- Execute este script em bancos que já receberam 001_initial_schema.sql.
create table if not exists mercado_livre_credentials (
    id integer primary key check (id = 1),
    access_token text not null,
    refresh_token text not null,
    expires_at timestamptz not null,
    updated_at timestamptz not null default now()
);

comment on table mercado_livre_credentials is
    'Estado OAuth rotativo do Mercado Livre. Restringir acesso e nunca expor pela API.';

alter table mercado_livre_credentials enable row level security;
