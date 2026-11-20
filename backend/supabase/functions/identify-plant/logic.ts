// Parte pura (testabile) di `identify-plant`: controllo dell'immagine e lettura della risposta Pl@ntNet.

export const MAX_IMAGE_BYTES = 4 * 1024 * 1024;

export interface PlantResult {
  scientific_name: string;
  genus: string;
  family: string;
  common_names: string[];
  score: number;
}

/** JPEG o PNG, non vuota, non oltre il limite. */
export function checkImage(bytes: Uint8Array): "jpeg" | "png" | "empty" | "too_large" | "unsupported" {
  if (bytes.length === 0) return "empty";
  if (bytes.length > MAX_IMAGE_BYTES) return "too_large";
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "jpeg";
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return "png";
  return "unsupported";
}

// deno-lint-ignore no-explicit-any
type Json = any;

/** genus/family possono essere una stringa o un oggetto { scientificNameWithoutAuthor }. */
function taxonName(value: Json): string {
  if (typeof value === "string") return value;
  if (value && typeof value === "object") {
    return String(value.scientificNameWithoutAuthor ?? value.scientificName ?? "");
  }
  return "";
}

/** Prime [limit] specie dalla risposta di POST /v2/identify/{project}. */
export function parseResults(body: Json, limit = 5): PlantResult[] {
  const results: Json[] = Array.isArray(body?.results) ? body.results : [];
  return results
    .filter((r) => r && typeof r.score === "number" && r.species)
    .slice(0, limit)
    .map((r) => {
      const s = r.species;
      const name = String(s.scientificNameWithoutAuthor ?? s.scientificName ?? "").trim();
      return {
        scientific_name: name,
        genus: taxonName(s.genus) || name.split(" ")[0],
        family: taxonName(s.family),
        common_names: (Array.isArray(s.commonNames) ? s.commonNames : [])
          .filter((n: Json) => typeof n === "string" && n.trim() !== "")
          .slice(0, 3)
          .map((n: string) => n.trim()),
        score: Math.max(0, Math.min(1, r.score)),
      };
    })
    .filter((r) => r.scientific_name !== "");
}

/** URL della chiamata: chiave solo lato server, italiano, niente immagini correlate (più leggero). */
export function plantNetUrl(apiKey: string, project = "all", results = 5): string {
  const params = new URLSearchParams({
    "api-key": apiKey,
    lang: "it",
    "nb-results": String(results),
    "include-related-images": "false",
  });
  return `https://my-api.plantnet.org/v2/identify/${encodeURIComponent(project)}?${params}`;
}
