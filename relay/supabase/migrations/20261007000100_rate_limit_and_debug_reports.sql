-- Per-client rate limiting for /start (hashed IP, never the raw address).
alter table public.signin_sessions add column client_hash text;
create index signin_sessions_client_created_idx on public.signin_sessions (client_hash, created_at);

-- Debug reports uploaded from the car (no adb on the Polestar). Contains the app's own redacted log only.
create table public.debug_reports (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  client_hash text,
  app_version text,
  device text,
  report text not null
);
alter table public.debug_reports enable row level security;
revoke all on public.debug_reports from anon, authenticated;
create index debug_reports_created_idx on public.debug_reports (created_at desc);
comment on table public.debug_reports is
  'AAOSMusic in-car debug reports (redacted app log, environment). Written only by the signin Edge Function.';
