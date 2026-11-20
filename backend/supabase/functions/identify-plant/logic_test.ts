import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1.0.13";
import { checkImage, MAX_IMAGE_BYTES, parseResults, plantNetUrl } from "./logic.ts";

Deno.test("images: jpeg and png accepted, others rejected", () => {
  assertEquals(checkImage(new Uint8Array([0xff, 0xd8, 0xff, 0xe0])), "jpeg");
  assertEquals(checkImage(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d])), "png");
  assertEquals(checkImage(new Uint8Array([0x47, 0x49, 0x46, 0x38])), "unsupported");
  assertEquals(checkImage(new Uint8Array()), "empty");
  assertEquals(checkImage(new Uint8Array(MAX_IMAGE_BYTES + 1)), "too_large");
});

Deno.test("results: nested genus/family, common names, score clamped", () => {
  const body = {
    results: [
      {
        score: 0.82,
        species: {
          scientificNameWithoutAuthor: "Monstera deliciosa",
          scientificName: "Monstera deliciosa Liebm.",
          genus: { scientificNameWithoutAuthor: "Monstera" },
          family: { scientificNameWithoutAuthor: "Araceae" },
          commonNames: ["Monstera", "Filodendro", "", "a", "b"],
        },
      },
      { score: 1.4, species: { scientificNameWithoutAuthor: "Rosa", genus: "Rosa", family: "Rosaceae" } },
      { score: "x", species: {} },
    ],
    remainingIdentificationRequests: 498,
  };
  assertEquals(parseResults(body), [
    {
      scientific_name: "Monstera deliciosa",
      genus: "Monstera",
      family: "Araceae",
      common_names: ["Monstera", "Filodendro", "a"],
      score: 0.82,
    },
    { scientific_name: "Rosa", genus: "Rosa", family: "Rosaceae", common_names: [], score: 1 },
  ]);
  assertEquals(parseResults({}), []);
  assertEquals(parseResults(null), []);
});

Deno.test("url: key in query, italian, no related images", () => {
  const url = plantNetUrl("k3y");
  assertStringIncludes(url, "https://my-api.plantnet.org/v2/identify/all?");
  assertStringIncludes(url, "api-key=k3y");
  assertStringIncludes(url, "lang=it");
  assertStringIncludes(url, "include-related-images=false");
});
