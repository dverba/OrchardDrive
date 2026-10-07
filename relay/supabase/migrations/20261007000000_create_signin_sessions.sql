-- Short-lived QR sign-in sessions relaying an Apple Music User Token from a phone to the car.
-- Only the `signin` Edge Function (service role) touches this table: RLS is on with no policies,
-- so the anon/authenticated API roles can't read or write it.
create table public.signin_sessions (
  id uuid primary key default gen_random_uuid(),          -- secret, known only to the car (used to poll)
  code text not null unique,                              -- short code carried in the QR / typed by user
  developer_token text not null,                          -- MusicKit developer token for the phone page
  user_token text,                                        -- Music User Token, set by the phone page
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  expires_at timestamptz not null default now() + interval '5 minutes'
);

alter table public.signin_sessions enable row level security;
revoke all on public.signin_sessions from anon, authenticated;

create index signin_sessions_expires_at_idx on public.signin_sessions (expires_at);

comment on table public.signin_sessions is
  'QR sign-in relay (AAOSMusic). Rows are deleted on pickup or after expiry; user_token never persists beyond 5 minutes.';
