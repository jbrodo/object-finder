# Piano: consumi acqua e analisi Object Finder

## Obiettivo e decisioni concordate

Estendere Object Finder con una web app e funzionalità Android per analizzare l'inventario e i
consumi d'acqua, usando gli stessi progetti e account Supabase. I consumi sono legati alla
posizione di un edificio. Ogni progetto deve poter abilitare le funzionalità e attribuire permessi
specifici alle singole utenze.

Le tabelle fotografate contengono consumi di periodo, anche di anni a confronto (ad esempio anno X
e anno X−1). Chi importa controlla e corregge i valori prima di confermarli: l'OCR non pubblica
automaticamente i dati.

Il repository di riferimento `jbrodo/consumi-acqua` contiene un report Streamlit basato su Excel,
con viste per periodo, scala e appartamento, tabelle, minimi e massimi, grafici a linee e a barre.
Include dati di acqua fredda (AFS), acqua calda (ACS), riscaldamento e costi. Prima di riprodurre i
calcoli occorre verificare significato delle colonne e formule del file originale.

## Architettura proposta

- **Web:** applicazione responsive per telefono e computer, con distribuzione prevista su Vercel.
- **Android:** funzionalità integrate nell'app esistente, mantenendo la sua architettura offline-first.
- **Backend comune:** Supabase Auth, Postgres e Storage; gli stessi progetti e utenti dell'inventario.
- **Edificio:** entità proposta con indirizzo e posizione quando disponibili, associabile ai luoghi
  strutturati esistenti. Un luogo non è necessariamente un edificio. La modellazione e l'eventuale
  migrazione dei dati esistenti vanno definite prima dell'implementazione.
- **Provenienza:** ogni valore importato deve poter essere ricondotto al documento e alla riga di
  origine, conservando anche le correzioni e lo stato di verifica.

## Funzionalità e autorizzazioni

Prevedere due livelli distinti:

1. Abilitazione delle funzionalità sul progetto: inventario, analisi inventario, consumi acqua e
   importazione dei documenti, secondo la configurazione scelta.
2. Permessi per membro e funzionalità, ad esempio visualizzare, importare, correggere e amministrare.

I ruoli di progetto esistenti restano la base da cui partire; la matrice definitiva di ruoli,
permessi e valori predefiniti è da definire. Owner e admin potranno gestire le abilitazioni secondo
le autorizzazioni effettivamente previste. Applicare le regole anche a Postgres, Storage e servizi
di elaborazione, con RLS e test: nascondere una funzione nell'interfaccia non limita l'accesso ai
dati. Alcuni membri dello stesso progetto potrebbero non essere autorizzati ai consumi.

## Consumi e dati

Prevedere condomìni o edifici, eventuali scale, unità immobiliari, periodi, documenti importati e
righe confermate. Registrare separatamente il tipo di grandezza, il valore, l'unità di misura e il
periodo a cui si riferisce. I valori delle tabelle indicate sono **consumi di periodo**, non letture
cumulative da sottrarre; altri tipi di dato richiedono una distinzione esplicita.

La consultazione per edificio comprende:

- filtro per periodo, scala e unità immobiliare;
- viste tabellari e grafici per consumi e grandezze autorizzate, con confronto fra periodi;
- minimi, massimi e dettaglio della fonte;
- evidenza di dati mancanti o non confrontabili, senza convertirli in zero;
- esportazione CSV da valutare con gli utenti.

Calcolare confronti e variazioni solo tra grandezze, unità e periodi compatibili. Confermare se
includere fin dalla prima versione anche riscaldamento e costi presenti nel report esistente.

## Importazione e OCR

Excel e foto devono alimentare un processo di revisione comune:

1. Selezionare edificio e file; per Excel scegliere fogli e colonne, per le foto acquisire o caricare
   l'immagine e prepararla, ad esempio correggendo orientamento e ritaglio.
2. Estrarre struttura della tabella, periodi e valori, mantenendo la fonte.
3. Mostrare fonte e dati estratti insieme, con celle modificabili e valori incerti evidenziati.
4. Controllare date, numeri in formato italiano, unità, periodi, duplicati e possibili anomalie.
5. Pubblicare nelle analisi solo le righe confermate da chi importa; consentire anche correzione e
   inserimento manuale quando l'estrazione è incompleta.

Valutare il motore OCR su un campione di foto reali prima di sceglierlo, confrontando ricostruzione
di righe e colonne, accuratezza, costi, tempi e trattamento dei dati. Prevedere documenti in Storage
privato con accesso coerente con le autorizzazioni; definire conservazione ed eliminazione delle
immagini. Il caricamento diretto verso Storage può evitare il passaggio di file grandi attraverso
una funzione Vercel.

### Unlimited-OCR 2: valutazione e prova locale

[Unlimited-OCR](https://github.com/baidu/Unlimited-OCR) è il candidato da valutare. Il progetto
documenta Unlimited-OCR 2 con server SGLang e API compatibile con chat completions; l'esempio di
inferenza Transformers è stato provato su NVIDIA A800 da 80 GB. È un esempio documentato, non un
requisito minimo verificato. Web e Android richiederebbero un servizio di inferenza separato; non è
stato verificato il funzionamento su Vercel o sul dispositivo Android. Verificare separatamente
licenza dei pesi, infrastruttura, costi e trattamento dei documenti prima dell'uso effettivo.

È disponibile un client sperimentale per un server Unlimited-OCR-2 **già avviato sulla stessa
macchina** (non installa né avvia il modello):

```bash
python3 backend/scripts/water_ocr.py foto.jpg --endpoint http://127.0.0.1:10000
python3 -m unittest discover -s backend/tests -p 'test_water_*.py'
```

Accetta JPEG o PNG fino a 10 MiB, impedisce proxy e redirect e accetta solo endpoint loopback.
Stampa il testo grezzo sulla console: può contenere dati personali e va protetto. Per sperimentare
l'estrazione di tabelle HTML semplici dall'output (anche dai blocchi grezzi Unlimited-OCR 2):

```bash
python3 backend/scripts/water_ocr.py foto.jpg > testo-ocr.txt
python3 backend/scripts/water_ocr_table.py testo-ocr.txt > candidati-ocr.json
```

L'adattatore riconosce intestazioni `APPARTAMENTO`, `ANNO` e colonne AFS/ACS, eventualmente con
anno a quattro cifre. Mantiene testo numerico originale e posizione logica nella tabella; non
deduce date e contrassegna **tutti** i valori OCR da controllare. Salta tabelle con celle unite,
righe disallineate e intestazioni non riconosciute, segnalando il motivo. Non crea righe in
Supabase; i file prodotti possono contenere dati personali. I test usano tabelle sintetiche e
simulano il server: una prova reale e una valutazione su fotografie rappresentative restano da fare.
Le istruzioni upstream richiedono anche codice del modello eseguito con `trust_remote_code`:
verificare provenienza e versione prima di installarlo in un ambiente isolato.

## Analisi inventario

Progettare una sezione filtrabile per progetto, edificio e luogo, con un primo insieme di indicatori:

- oggetti per edificio, luogo, categoria e stato;
- oggetti in prestito e archiviati;
- distribuzione su mappa quando la posizione è disponibile;
- oggetti senza collocazione strutturata.

Inventario e consumi condividono il riferimento all'edificio ma restano grandezze distinte. Eventuali
correlazioni richiedono una domanda specifica e dati adeguati.

## Web e Android

Progettare entrambe le esperienze con lo stesso modello e gli stessi permessi. La web app è adatta
alle tabelle estese e all'amministrazione; Android integra consultazione e acquisizione fotografica
nei flussi esistenti. Stabilire quali operazioni amministrative e quali importazioni Excel offrire
su Android.

Definire quali dati dei consumi siano disponibili offline su Android e come gestire eventuali
modifiche in attesa di sincronizzazione. L'OCR può inizialmente richiedere connessione. Un rilascio
per fasi permette di verificare modello e calcoli sul web prima di completare tutti i flussi Android.

### Prima web app: consultazione

La directory `web/` contiene una prima interfaccia di sola lettura: login con l'account Supabase,
selezione progetto ed edificio, filtri per tipo d'acqua, appartamento e intervallo, e tabella dei
consumi. La tabella indica l'ultima modifica e, per chi ha anche `water/import`, la fonte registrata;
in assenza di permesso o dati la fonte è indicata come non disponibile. I filtri sono applicati ai
dati caricati per l'edificio e non rimuovono il limite attuale di 20.000 righe. Il token resta solo
in memoria; alla scadenza occorre accedere di nuovo. La funzione acqua e il permesso `water/view`
devono essere attivi: la stessa RLS del database rimane responsabile dell'accesso ai dati.

Per provarla in locale, copiare `web/config.example.js` in `web/config.js`, inserire URL e chiave
**pubblica** anon/publishable e avviare `python3 -m http.server 8000 --directory web`; aprire
`http://localhost:8000`. `config.js` è ignorato da Git e non deve contenere service role o secret key.
La configurazione deve puntare a un database su cui siano già state applicate le migrazioni richieste.

Per una futura distribuzione Vercel collegata al repository: impostare Root Directory `web`, Build
Command `npm run build` e Output Directory `dist`, con le variabili pubbliche `PUBLIC_SUPABASE_URL`
e `PUBLIC_SUPABASE_ANON_KEY`. La build incorpora questi valori nel sito visibile ai visitatori:
non usare credenziali privilegiate. Non è ancora stata effettuata una distribuzione né una prova
end-to-end con Supabase reale. Importazione, revisione, grafici e gestione permessi non sono ancora
disponibili nell'interfaccia web.

## Fasi di realizzazione

**Avvio implementazione:** la migration 018 introduce edifici e un primo meccanismo di funzionalità
opzionali con permessi espliciti per membro. Le funzionalità nuove sono disattivate finché non vengono
abilitate; anche owner e admin devono ricevere il permesso specifico per usarle. Owner e admin possono
gestire abilitazioni e permessi. L'inventario esistente mantiene le proprie policy. Gli edifici sono
visibili ai membri del progetto; eliminare il luogo associato non elimina l'edificio. Non sono ancora
disponibili schermate, importazione o OCR. Le decisioni sotto restano aperte.

La migration 019 aggiunge unità immobiliari e consumi confermati di acqua fredda e calda in metri
cubi, per intervalli di date, con controllo dei duplicati. La lettura richiede `water/view`, la
creazione e modifica richiedono `water/edit`, l'eliminazione richiede `water/manage`; per modificare
o eliminare righe visibili occorre anche `water/view`. **L'accesso in lettura è per l'intero progetto**, non per singola unità:
non assegnare `water/view` a chi deve vedere soltanto il proprio appartamento finché non sarà
definita e implementata la restrizione per unità. I permessi sono indipendenti: concedere `edit` o
`manage` non concede automaticamente `view`.

La migration 020 aggiunge bozze di importazione e righe modificabili con nome della fonte e
riferimento alla riga. `confirm_water_import` verifica i permessi `view`, `import` ed `edit` e
conferma atomicamente tutte le righe complete, mantenendo il collegamento ai consumi creati. Un
duplicato annulla la conferma; le righe confermate restano immutabili. È solo la base dati: non sono
ancora implementati caricamento dei file, riconoscimento OCR, estrazione Excel o schermate. Il nome
del file non equivale alla conservazione del documento originale.

È disponibile un primo estrattore locale per il formato Excel del repository di riferimento:

```bash
python3 backend/scripts/water_xlsx.py percorso/al/file.xlsx > candidati.json
python3 -m unittest discover -s backend/tests -p test_water_xlsx.py
```

Produce candidati JSON con unità, tipo acqua, valore, sigla del periodo e cella d'origine. Non invia
dati a Supabase e non crea ancora bozze automaticamente: date iniziali e finali rimangono da assegnare
in revisione, perché sigle come `18-20` non stabiliscono date precise. Le celle con formule vengono
segnalate: l'estrattore legge il valore eventualmente memorizzato nel file, senza ricalcolarlo.
Il JSON può contenere dati dell'edificio: conservarlo in un percorso privato. Le intestazioni
supportate sono `APPARTAMENTO`, `ANNO`, `AFS LETTURA` e `ACS LETTURA (MC)`; altri formati devono
essere mappati o supportati in seguito.

La migration 021 e `backend/scripts/water_stage.py` consentono di inviare questi candidati come
**bozza**, senza confermare i consumi. Servono le migration fino alla 021, un progetto e un edificio
esistenti, la funzionalità acqua attiva e i permessi utente `water/view` e `water/import`. Configurare
`SUPABASE_URL`, `SUPABASE_ANON_KEY` (chiave pubblica) e `WATER_USER_ACCESS_TOKEN` (JWT di una
sessione utente) nell'ambiente, senza inserirli nei file o nella cronologia dei comandi:

```bash
python3 backend/scripts/water_stage.py candidati.json --project UUID_PROGETTO --building UUID_EDIFICIO --source-name prospetto.xlsx --source-type xlsx
```

L'invio è atomico, ammette 1–1000 righe e rifiuta il JSON se segnala tabelle o fogli saltati.
L'identificativo della bozza viene mostrato prima della richiesta: se la risposta si interrompe,
verificare l'ID sul server prima di riprovare; `--import-id` permette di riutilizzare lo stesso
UUID senza creare una seconda bozza. Valori e sigle originali restano distinti dai campi da
correggere; unità e date rimangono da assegnare. La revisione grafica e la conservazione del
documento originale non sono ancora implementate. Il client è testato con risposte HTTP simulate;
non è stato verificato contro un'istanza PostgREST reale.

1. **Analisi delle fonti:** esaminare Excel e foto rappresentative; definire campi, periodi, unità,
   regole di calcolo e casi particolari.
2. **Permessi:** concordare una matrice funzionalità × ruolo × operazione, inclusa la visibilità tra
   unità immobiliari.
3. **Dati condivisi:** progettare edifici, associazioni ai luoghi, consumi, importazioni e provenienza;
   realizzare migrazioni, policy e test di isolamento.
4. **Web iniziale:** accesso, edifici, importazione Excel e manuale, dashboard consumi e analisi
   inventario.
5. **Android:** consultazione di edifici, consumi e analisi con permessi coerenti e comportamento
   offline definito.
6. **Prova OCR:** misurare la qualità su foto reali e scegliere il motore.
7. **Importazione fotografica:** revisione e conferma su web e Android.
8. **Verifica e rilascio:** confrontare i dati con gli originali, provare utenti con permessi diversi,
   gestire errori e duplicati, quindi predisporre distribuzione web e rilascio Android.

## Decisioni aperte

- Un utente abilitato ai consumi vede tutte le unità dell'edificio o può essere limitato alla propria?
- Quali ruoli possono abilitare funzionalità, assegnare permessi e modificare dati già confermati?
- Quali colonne di riscaldamento e costo dell'Excel rientrano nella prima versione?
- Quali operazioni e dati devono funzionare offline su Android?
- Quali funzioni amministrative e di importazione Excel servono anche su Android?
- Per quanto tempo conservare foto e documenti, e chi può eliminarli?
- Quale servizio OCR offre risultati, costi e trattamento dei dati adatti ai documenti reali?
