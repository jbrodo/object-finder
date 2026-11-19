#!/usr/bin/env bash
# Applica le migration di backend/migrations in ordine, una sola volta ciascuna.
#
#   DATABASE_URL=postgresql://... backend/scripts/migrate.sh            # applica le pendenti
#   DATABASE_URL=... backend/scripts/migrate.sh --dry-run               # mostra cosa farebbe
#   DATABASE_URL=... backend/scripts/migrate.sh --baseline 007          # segna 001..007 come già
#                                                                         # applicate (eseguite a mano)
# Regole:
# - registro in ops.schema_migrations (schema non esposto da PostgREST);
# - ogni file gira in una singola transazione insieme alla sua registrazione: o tutto o niente;
# - una migration già applicata non può cambiare (checksum SHA-256): se serve una modifica,
#   si crea una nuova migration.
set -euo pipefail

DIR="${MIGRATIONS_DIR:-$(cd "$(dirname "$0")/../migrations" && pwd)}"
DRY_RUN=false
BASELINE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --baseline) BASELINE="${2:?--baseline richiede una versione, es. 007}"; shift 2 ;;
    *) echo "Argomento sconosciuto: $1" >&2; exit 2 ;;
  esac
done
: "${DATABASE_URL:?Impostare DATABASE_URL (stringa di connessione Postgres)}"
if [[ -n "$BASELINE" && ! "$BASELINE" =~ ^[0-9]+$ ]]; then
  echo "--baseline accetta solo una versione numerica (es. 007), ricevuto: $BASELINE" >&2; exit 2
fi

PSQL=(psql "$DATABASE_URL" -X -q -v ON_ERROR_STOP=1 --no-psqlrc)
log() { echo "[migrate] $*"; }

"${PSQL[@]}" <<'SQL'
create schema if not exists ops;
revoke all on schema ops from public;
create table if not exists ops.schema_migrations (
    version    text primary key,
    filename   text not null,
    checksum   text not null,
    applied_at timestamptz not null default now(),
    baseline   boolean not null default false
);
SQL

applied_checksum() {
  "${PSQL[@]}" -At -v v="$1" <<'SQL'
select checksum from ops.schema_migrations where version = :'v';
SQL
}

shopt -s nullglob
files=("$DIR"/[0-9]*_*.sql)
if [[ ${#files[@]} -eq 0 ]]; then log "Nessuna migration in $DIR"; exit 0; fi
IFS=$'\n' files=($(printf '%s\n' "${files[@]}" | sort)); unset IFS

# Prima di toccare il database: nomi validi (finiscono in SQL) e versioni uniche.
for f in "${files[@]}"; do
  if [[ ! "$(basename "$f")" =~ ^[0-9]+_[A-Za-z0-9_.-]+\.sql$ ]]; then
    log "ERRORE: nome non valido: $(basename "$f") (atteso NNN_descrizione.sql)"; exit 1
  fi
done
# Prima di toccare il database: ogni versione deve essere unica.
dupes=$(for f in "${files[@]}"; do basename "$f" | cut -d_ -f1; done | sort | uniq -d)
if [[ -n "$dupes" ]]; then log "ERRORE: versioni duplicate: $dupes"; exit 1; fi

# Database già popolato a mano ma registro vuoto: rieseguire 001 fallirebbe a metà strada.
count=$("${PSQL[@]}" -At -c "select count(*) from ops.schema_migrations")
if [[ "$count" == "0" && -z "$BASELINE" ]]; then
  exists=$("${PSQL[@]}" -At -c "select to_regclass('public.objects') is not null")
  if [[ "$exists" == "t" ]]; then
    log "ERRORE: il database contiene già lo schema (public.objects) ma il registro è vuoto."
    log "Se le migration sono state eseguite a mano, lanciare con --baseline <ultima versione applicata>."
    exit 1
  fi
fi

pending=0
for f in "${files[@]}"; do
  name=$(basename "$f")
  version=${name%%_*}
  sum=$(sha256sum "$f" | cut -d' ' -f1)
  current=$(applied_checksum "$version")

  if [[ -n "$current" ]]; then
    if [[ "$current" != "$sum" ]]; then
      log "ERRORE: $name è già applicata ma il file è cambiato (checksum diverso)."
      log "Le migration applicate sono immutabili: creare una nuova migration con la modifica."
      exit 1
    fi
    continue
  fi

  if [[ -n "$BASELINE" && "$((10#$version))" -le "$((10#$BASELINE))" ]]; then
    if $DRY_RUN; then log "baseline (dry-run): $name"; continue; fi
    "${PSQL[@]}" -v v="$version" -v f="$name" -v c="$sum" <<'SQL'
insert into ops.schema_migrations(version, filename, checksum, baseline) values (:'v', :'f', :'c', true);
SQL
    log "baseline: $name segnata come applicata (non eseguita)"
    continue
  fi

  pending=$((pending + 1))
  if $DRY_RUN; then log "da applicare: $name"; continue; fi
  log "applico: $name"
  # File e registrazione nella stessa transazione: un errore annulla entrambi.
  {
    echo "begin;"
    cat "$f"
    echo
    echo "insert into ops.schema_migrations(version, filename, checksum) values ('$version', '$name', '$sum');"
    echo "commit;"
  } | "${PSQL[@]}"
  log "ok: $name"
done

if $DRY_RUN; then log "dry-run: $pending migration da applicare"; else log "completato: $pending migration applicate"; fi
