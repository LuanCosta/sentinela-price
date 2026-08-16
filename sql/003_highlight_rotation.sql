create table if not exists highlight_rotation (
    id integer primary key check (id = 1),
    next_index integer not null default 0 check (next_index >= 0),
    updated_at timestamptz not null default now()
);

comment on table highlight_rotation is
    'Cursor interno da rotação automática de categorias do recurso highlights.';

alter table highlight_rotation enable row level security;
