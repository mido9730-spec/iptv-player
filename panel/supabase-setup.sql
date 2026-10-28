-- Run this in Supabase: SQL Editor > New query > paste > Run.
-- Safe to run again: if you ran an older version, this upgrades it and keeps your data.

-- ---------- Sources: your library of playlists (M3U link or Xtream login) ----------
create table if not exists public.sources (
  id         uuid primary key default gen_random_uuid(),
  name       text not null,
  type       text not null check (type in ('m3u', 'xtream')),
  m3u_url    text not null default '',
  server     text not null default '',
  username   text not null default '',
  password   text not null default '',
  created_at timestamptz not null default now()
);

-- ---------- Devices: one row per TV ----------
create table if not exists public.devices (
  mac          text primary key
               check (mac ~ '^[0-9A-F]{2}(:[0-9A-F]{2}){5}$'),
  client_name  text        not null default '',
  playlist_url text        not null default '',
  expires_at   timestamptz,
  enabled      boolean     not null default true,
  notes        text        not null default '',
  created_at   timestamptz not null default now(),
  last_seen    timestamptz
);
alter table public.devices
  add column if not exists source_id uuid references public.sources(id) on delete restrict;

-- ---------- Codes: single-use activation codes you give to clients ----------
create table if not exists public.codes (
  code         text primary key check (code ~ '^[A-Z0-9]{12}$'),
  source_id    uuid references public.sources(id) on delete restrict,
  playlist_url text        not null default '',
  days         int         not null default 30 check (days >= 0),  -- 0 = no expiry
  note         text        not null default '',
  created_at   timestamptz not null default now(),
  used_by      text,
  used_at      timestamptz
);

-- ---------- Settings (one row) ----------
create table if not exists public.settings (
  id        int primary key default 1 check (id = 1),
  allow_own boolean not null default true   -- clients may sign in with their own M3U / Xtream
);
insert into public.settings (id) values (1) on conflict do nothing;

-- ---------- Access: only the signed-in admin can touch the tables ----------
alter table public.sources  enable row level security;
alter table public.devices  enable row level security;
alter table public.codes    enable row level security;
alter table public.settings enable row level security;

drop policy if exists "admin full access" on public.sources;
drop policy if exists "admin full access" on public.devices;
drop policy if exists "admin full access" on public.codes;
drop policy if exists "admin full access" on public.settings;
create policy "admin full access" on public.sources  for all to authenticated using (true) with check (true);
create policy "admin full access" on public.devices  for all to authenticated using (true) with check (true);
create policy "admin full access" on public.codes    for all to authenticated using (true) with check (true);
create policy "admin full access" on public.settings for all to authenticated using (true) with check (true);

revoke all on public.sources, public.devices, public.codes, public.settings from anon;

-- ---------- Helpers (not callable by the TV app) ----------
create or replace function public.source_link(s public.sources)
returns text
language sql
immutable
as $$
  select case
    when s.type = 'm3u' then s.m3u_url
    else
      (case when s.server ~* '^https?://' then rtrim(s.server, '/') else 'http://' || rtrim(s.server, '/') end)
      || '/get.php?username=' || s.username
      || '&password=' || s.password
      || '&type=m3u_plus&output=ts'
  end
$$;

create or replace function public.device_json(d public.devices)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  s   public.sources;
  url text := d.playlist_url;
  st  text;
  own boolean;
begin
  if d.source_id is not null then
    select * into s from public.sources where id = d.source_id;
    if found then url := public.source_link(s); end if;
  end if;

  select allow_own into own from public.settings where id = 1;

  if not d.enabled then
    st := 'disabled';
  elsif url is null or url = '' then
    st := 'pending';
  elsif d.expires_at is not null and d.expires_at < now() then
    st := 'expired';
  else
    st := 'active';
  end if;

  return json_build_object(
    'status', st,
    'playlist_url', case when st = 'active' then url else null end,
    'expires_at', d.expires_at,
    'allow_own', coalesce(own, true)
  );
end;
$$;
revoke all on function public.source_link(public.sources) from public, anon;
revoke all on function public.device_json(public.devices) from public, anon;

-- ---------- Called by the TV app ----------
create or replace function public.get_device(p_mac text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  m text := upper(trim(p_mac));
  d public.devices;
begin
  if m is null or m !~ '^[0-9A-F]{2}(:[0-9A-F]{2}){5}$' then
    return json_build_object('status', 'error');
  end if;

  insert into public.devices (mac, last_seen)
  values (m, now())
  on conflict (mac) do update set last_seen = now()
  returning * into d;

  return public.device_json(d);
end;
$$;

create or replace function public.redeem_code(p_mac text, p_code text)
returns json
language plpgsql
security definer
set search_path = public
as $$
declare
  m text := upper(trim(p_mac));
  c text := upper(regexp_replace(coalesce(p_code, ''), '[^A-Za-z0-9]', '', 'g'));
  k public.codes;
  d public.devices;
  base timestamptz;
begin
  if m is null or m !~ '^[0-9A-F]{2}(:[0-9A-F]{2}){5}$' then
    return json_build_object('status', 'error');
  end if;

  select * into k from public.codes where code = c for update;
  if not found then
    return json_build_object('status', 'invalid_code');
  end if;
  if k.used_by is not null then
    return json_build_object('status', 'used_code');
  end if;

  insert into public.devices (mac) values (m) on conflict (mac) do nothing;
  select * into d from public.devices where mac = m for update;

  -- A renewal code adds time on top of what is left.
  base := greatest(now(), coalesce(d.expires_at, now()));

  update public.devices set
    source_id    = k.source_id,
    playlist_url = k.playlist_url,
    expires_at   = case when k.days = 0 then null else base + make_interval(days => k.days) end,
    enabled      = true,
    last_seen    = now()
  where mac = m
  returning * into d;

  update public.codes set used_by = m, used_at = now() where code = c;

  return public.device_json(d);
end;
$$;

revoke all on function public.get_device(text) from public;
revoke all on function public.redeem_code(text, text) from public;
grant execute on function public.get_device(text) to anon, authenticated;
grant execute on function public.redeem_code(text, text) to anon, authenticated;
