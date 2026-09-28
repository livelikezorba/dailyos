-- DailyOS 스키마 — Supabase SQL Editor에 전체를 붙여넣고 Run 하세요.
-- 여러 번 실행해도 안전하도록 작성되어 있습니다.

-- ─────────────────────────────────────────────
-- 영역 (투자 / 건강 / 일본어 / 커리어 …)
-- ─────────────────────────────────────────────
create table if not exists public.categories (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name       text not null,
  color      text not null default '#4f7cff',
  sort       int  not null default 0,
  created_at timestamptz not null default now()
);

-- ─────────────────────────────────────────────
-- 루틴 항목
--   kind = 'check'  : 했다/안 했다 (영양제 A 복용)
--   kind = 'number' : 수치 (단어 N개, 운동 N분) — target 이상이면 달성
--   reminder_times  : 알림 시각 'HH:MM' 배열
--   days            : 요일 (1=월 … 7=일)
-- ─────────────────────────────────────────────
create table if not exists public.items (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null default auth.uid() references auth.users(id) on delete cascade,
  category_id    uuid references public.categories(id) on delete cascade,
  name           text not null,
  kind           text not null default 'check' check (kind in ('check','number')),
  unit           text,
  target         numeric,
  reminder_times text[]   not null default '{}',
  days           smallint[] not null default '{1,2,3,4,5,6,7}',
  active         boolean  not null default true,
  sort           int      not null default 0,
  start_day      date     not null default current_date,
  created_at     timestamptz not null default now()
);

-- ─────────────────────────────────────────────
-- 하루 기록 (항목 × 날짜 1행)
-- ─────────────────────────────────────────────
create table if not exists public.logs (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  item_id    uuid not null references public.items(id) on delete cascade,
  day        date not null,
  done       boolean not null default false,
  value      numeric,
  note       text,
  device     text,
  updated_at timestamptz not null default now(),
  unique (item_id, day)
);
create index if not exists logs_user_day on public.logs(user_id, day);

-- ─────────────────────────────────────────────
-- 기기 / 앱 사용량 / 사용 한도
-- ─────────────────────────────────────────────
create table if not exists public.devices (
  user_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id text not null,
  name      text not null,
  last_seen timestamptz not null default now(),
  primary key (user_id, device_id)
);

create table if not exists public.app_usage (
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  text not null,
  day        date not null,
  package    text not null,
  label      text,
  minutes    int  not null default 0,
  updated_at timestamptz not null default now(),
  primary key (user_id, device_id, day, package)
);
create index if not exists app_usage_user_day on public.app_usage(user_id, day);

create table if not exists public.usage_limits (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid() references auth.users(id) on delete cascade,
  package         text not null,
  label           text not null,
  daily_limit_min int  not null default 60,
  unique (user_id, package)
);

-- ─────────────────────────────────────────────
-- 보안: 본인 데이터만 읽고 쓰기 (Row Level Security)
-- ─────────────────────────────────────────────
do $$
declare t text;
begin
  foreach t in array array['categories','items','logs','devices','app_usage','usage_limits'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists own_rows on public.%I', t);
    execute format(
      'create policy own_rows on public.%I for all to authenticated
         using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
  end loop;
end $$;

-- ─────────────────────────────────────────────
-- 수치 누적 (알림에서 "+20" 입력 → 오늘 값에 더함)
-- ─────────────────────────────────────────────
create or replace function public.add_log_value(p_item uuid, p_day date, p_delta numeric, p_device text default null)
returns public.logs
language plpgsql
security invoker
as $$
declare
  v_target numeric;
  r public.logs;
begin
  select target into v_target from public.items where id = p_item and user_id = auth.uid();
  if not found then raise exception 'item not found'; end if;

  insert into public.logs (user_id, item_id, day, value, done, device, updated_at)
  values (auth.uid(), p_item, p_day, p_delta,
          case when v_target is null then p_delta > 0 else p_delta >= v_target end,
          p_device, now())
  on conflict (item_id, day) do update
     set value = coalesce(public.logs.value, 0) + excluded.value,
         done  = case when v_target is null then coalesce(public.logs.value, 0) + excluded.value > 0
                      else coalesce(public.logs.value, 0) + excluded.value >= v_target end,
         device = excluded.device,
         updated_at = now()
  returning * into r;
  return r;
end $$;

grant execute on function public.add_log_value(uuid, date, numeric, text) to authenticated;
