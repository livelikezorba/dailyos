-- DailyOS v2 — 일본어(Anki) · 건강 · 수면 · 목표 설정
-- Supabase SQL Editor에 전체를 붙여넣고 Run 하세요. (schema.sql을 이미 실행한 상태에서)
-- 여러 번 실행해도 안전합니다.

-- 목표·설정 (Anki 덱, 목표일, 수면 목표, 점수 비중 등) — JSON 한 덩어리
create table if not exists public.user_settings (
  user_id    uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  data       jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- Anki 하루 기록 (Anki 기준 하루: 새벽 4시에 넘어감)
create table if not exists public.anki_daily (
  user_id        uuid not null default auth.uid() references auth.users(id) on delete cascade,
  day            date not null,
  decks          text,
  new_studied    int,   -- 오늘 처음 공부한 새 카드
  reviewed       int,   -- 오늘 답한 카드 (새 카드 포함)
  remaining_new  int,   -- 덱에 남은 새 카드
  total_cards    int,
  mature         int,   -- 간격 21일 이상
  due_left       int,   -- 오늘 남은 복습(학습+복습)
  required_new   int,   -- 목표일 기준 오늘 필요한 새 카드
  updated_at     timestamptz not null default now(),
  primary key (user_id, day)
);

-- 건강 (허리): 걷기 · 푸시업 · 통증 아침/저녁
create table if not exists public.health_log (
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  day        date not null,
  walk_min   numeric,
  walk_km    numeric,
  pushups    int,
  pain_am    smallint check (pain_am between 0 and 10),
  pain_pm    smallint check (pain_pm between 0 and 10),
  note_am    text,
  note_pm    text,
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);

-- 수면 계산용: 기기별 0시~12시 폰 사용 구간 [[시작분, 끝분], …] (0시 기준 분)
create table if not exists public.night_activity (
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  text not null,
  day        date not null,
  sessions   jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (user_id, device_id, day)
);

do $$
declare t text;
begin
  foreach t in array array['user_settings','anki_daily','health_log','night_activity'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists own_rows on public.%I', t);
    execute format(
      'create policy own_rows on public.%I for all to authenticated
         using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
  end loop;
end $$;

-- 걷기·푸시업 누적 입력 (알림에서 "30 2.1" 입력 → 오늘 값에 더함)
create or replace function public.add_health(p_day date, p_walk_min numeric default 0, p_walk_km numeric default 0, p_pushups int default 0)
returns public.health_log
language plpgsql
security invoker
as $$
declare r public.health_log;
begin
  insert into public.health_log (user_id, day, walk_min, walk_km, pushups, updated_at)
  values (auth.uid(), p_day, nullif(coalesce(p_walk_min,0),0), nullif(coalesce(p_walk_km,0),0), nullif(coalesce(p_pushups,0),0), now())
  on conflict (user_id, day) do update
     set walk_min = nullif(coalesce(public.health_log.walk_min,0) + coalesce(p_walk_min,0), 0),
         walk_km  = nullif(coalesce(public.health_log.walk_km,0)  + coalesce(p_walk_km,0), 0),
         pushups  = nullif(coalesce(public.health_log.pushups,0)  + coalesce(p_pushups,0), 0),
         updated_at = now()
  returning * into r;
  return r;
end $$;

grant execute on function public.add_health(date, numeric, numeric, int) to authenticated;
