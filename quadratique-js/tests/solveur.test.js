"use strict";

/**
 * ============================================================================
 *  HARNAIS DE TEST V&V — Solveur d'équations du second degré  a·x² + b·x + c = 0
 * ============================================================================
 *
 *  Organisation du fichier :
 *
 *    1. Découplage & chargement dynamique du solveur   (≈ réflexivité)
 *    2. Les cas limites, lus dynamiquement dans VALEURS_TESTS (9 valeurs du sujet par défaut)
 *    3. Générateur combinatoire All-Pairs glouton sur les natures (sans jenny.exe)
 *    4. Oracle mathématique indépendant (Viète + résidu, epsilon machine)
 *    5. Tests paramétrés Jest (test.each → une bulle verte/rouge par cas)
 *
 *  Lancement :   npm test
 *  Autre solveur : SOLVEUR_PATH=chemin/vers/module.js npm test
 *  Autres valeurs : VALEURS_TESTS="-3, 0, 2, 1e9" npm test   (voir section 2)
 *
 *  Ce fichier ne contient AUCUN `require` / `import` statique du solveur :
 *  il compile et s'exécute même si src/solveur.js n'existe pas encore (TDD).
 *  Dans ce cas, chaque cas de test est marqué ÉCHOUÉ avec un message explicite.
 * ============================================================================
 */

// ============================================================================
// 1. DÉCOUPLAGE ABSOLU & CHARGEMENT DYNAMIQUE
// ============================================================================

/**
 * Chemin SUPPOSÉ du module à tester, sous forme de simple chaîne de caractères.
 * Rien n'est chargé à cet endroit : ce n'est qu'une donnée.
 * Il peut être remplacé par la variable d'environnement SOLVEUR_PATH.
 */
const CHEMIN_SOLVEUR = process.env.SOLVEUR_PATH || `${__dirname}/../src/solveur.js`;

/** Nom d'un objet global qu'on peut injecter à la place d'un fichier. */
const NOM_GLOBAL_INJECTE = "SOLVEUR_QUADRATIQUE";

/** Noms de fonction acceptés dans le module chargé (détection dynamique). */
const NOMS_FONCTION_CANDIDATS = ["resoudre", "resoudreEquation", "solve", "solveQuadratic"];

/**
 * Recherche dynamique de la fonction de résolution.
 * Ordre : 1) objet global injecté, 2) module chargé à partir de CHEMIN_SOLVEUR.
 * Ne lève jamais d'exception : renvoie { fonction } ou { erreur }.
 */
function chargerSolveurDynamiquement() {
  try {
    // 1) Injection d'un objet global (ex. globalThis.SOLVEUR_QUADRATIQUE = fn)
    const injecte = globalThis[NOM_GLOBAL_INJECTE];
    if (injecte !== undefined) {
      const fonction = extraireFonction(injecte);
      if (fonction) return { fonction, origine: `globalThis.${NOM_GLOBAL_INJECTE}` };
    }

    // 2) Chargement à l'exécution d'un module dont le chemin est une variable.
    //    Le lien n'existe qu'au moment de l'appel : aucune liaison statique.
    const charge = require(CHEMIN_SOLVEUR);
    const fonction = extraireFonction(charge);
    if (!fonction) {
      return {
        erreur:
          `Le module "${CHEMIN_SOLVEUR}" a été trouvé, mais n'expose aucune fonction ` +
          `parmi : ${NOMS_FONCTION_CANDIDATS.join(", ")}.`,
      };
    }
    return { fonction, origine: CHEMIN_SOLVEUR };
  } catch (e) {
    return {
      erreur:
        `Module de calcul introuvable ou invalide : "${CHEMIN_SOLVEUR}". ` +
        `(${e && e.message ? e.message.split("\n")[0] : e})`,
    };
  }
}

/** Le module peut exporter directement une fonction, ou un objet qui la contient. */
function extraireFonction(candidat) {
  if (typeof candidat === "function") return candidat;
  if (candidat && typeof candidat === "object") {
    for (const nom of NOMS_FONCTION_CANDIDATS) {
      if (typeof candidat[nom] === "function") return candidat[nom];
    }
  }
  return null;
}

// ============================================================================
// 2. LES CAS LIMITES — DICTIONNAIRE DYNAMIQUE (BOÎTE NOIRE & IEEE 754)
// ============================================================================

/**
 * Les valeurs injectées dans a, b et c ne sont PAS figées dans le code :
 * elles sont lues au lancement dans la variable d'environnement VALEURS_TESTS.
 *
 * Deux formats acceptés :
 *   - une liste de nombres (séparés par virgules, points-virgules ou espaces) :
 *       VALEURS_TESTS="-1e5, -5, -0.1, -1e-6, 0, 1e-6, 0.1, 5, 1e5"
 *     → les noms (GRAND_NEGATIF, NUL, …) sont déduits automatiquement ;
 *   - un dictionnaire JSON { nom: valeur } :
 *       VALEURS_TESTS='{"PETIT": -0.5, "NUL": 0, "ENORME": 1e8}'
 *
 * Exemples de lancement :
 *   bash        : VALEURS_TESTS="-3, 0, 2, 1e9" npm test
 *   PowerShell  : $env:VALEURS_TESTS="-3, 0, 2, 1e9"; npm test
 *   cmd.exe     : set VALEURS_TESTS=-3, 0, 2, 1e9 && npm test
 *
 * Sans VALEURS_TESTS, la suite utilise par défaut les 9 états limites du sujet,
 * qui mélangent ordres de grandeur extrêmes (±1e5) et valeurs minuscules (±1e-6)
 * pour provoquer les erreurs classiques des flottants machine : perte de
 * chiffres significatifs (cancellation), Δ dominé par b², racines énormes, etc.
 */
const VALEURS_PAR_DEFAUT = Object.freeze([-100000.0, -5.0, -0.1, -0.000001, 0.0, 0.000001, 0.1, 5.0, 100000.0]);

/**
 * NATURE d'une valeur selon son ordre de grandeur (recalculée pour toute valeur) :
 *   NUL               x = 0
 *   TRES_PROCHE_ZERO  0 < |x| < 1e-3
 *   PROCHE_ZERO       1e-3 ≤ |x| < 1
 *   NOMINAL           1 ≤ |x| < 1e3
 *   GRAND             |x| ≥ 1e3
 */
function natureDe(valeur) {
  const v = Math.abs(valeur);
  if (v === 0) return "NUL";
  if (v < 1e-3) return "TRES_PROCHE_ZERO";
  if (v < 1) return "PROCHE_ZERO";
  if (v < 1e3) return "NOMINAL";
  return "GRAND";
}

function signeDe(valeur) {
  return valeur < 0 ? "NEGATIF" : valeur > 0 ? "POSITIF" : "NUL";
}

/** Nom lisible généré pour une valeur : ex. -5 → "NOMINAL_NEGATIF", 0 → "NUL". */
function nomAutomatique(valeur) {
  return valeur === 0 ? "NUL" : `${natureDe(valeur)}_${signeDe(valeur)}`;
}

/**
 * Construit le dictionnaire { nom: valeur } à partir d'une chaîne.
 * Lève une erreur explicite si la chaîne est invalide.
 */
function analyserValeurs(texte) {
  const brut = texte.trim();
  let entrees;

  if (brut.startsWith("{")) {
    let objet;
    try {
      objet = JSON.parse(brut);
    } catch (e) {
      throw new Error(`JSON invalide : ${e.message}`);
    }
    entrees = Object.entries(objet).map(([nom, v]) => [nom, Number(v)]);
  } else {
    const morceaux = brut.split(/[\s,;]+/).filter(Boolean);
    entrees = morceaux.map((m) => [null, Number(m)]);
    const invalide = morceaux.find((m) => !Number.isFinite(Number(m)));
    if (invalide !== undefined) throw new Error(`"${invalide}" n'est pas un nombre.`);
  }

  const dictionnaire = {};
  const dejaVues = new Set();
  for (const [nomDonne, valeur] of entrees) {
    if (!Number.isFinite(valeur)) throw new Error(`La valeur de "${nomDonne}" n'est pas un nombre fini.`);
    const v = valeur === 0 ? 0 : valeur; // -0 → 0
    if (dejaVues.has(v)) continue; // doublon ignoré
    dejaVues.add(v);
    // Noms uniques : "NOMINAL_POSITIF", puis "NOMINAL_POSITIF_2", …
    const base = nomDonne || nomAutomatique(v);
    let nom = base;
    for (let n = 2; nom in dictionnaire; n++) nom = `${base}_${n}`;
    dictionnaire[nom] = v;
  }
  if (Object.keys(dictionnaire).length < 2) {
    throw new Error("Il faut au moins 2 valeurs distinctes.");
  }
  return dictionnaire;
}

/**
 * Lecture de VALEURS_TESTS avec repli sur les valeurs du sujet.
 * Une valeur invalide ne fait pas planter la suite : on garde les valeurs par
 * défaut et un test dédié est marqué ÉCHOUÉ avec le message d'erreur.
 */
function chargerCasLimites() {
  const parDefaut = analyserValeurs(VALEURS_PAR_DEFAUT.join(","));
  const texte = process.env.VALEURS_TESTS;
  if (texte === undefined || texte.trim() === "") {
    return { casLimites: parDefaut, source: "valeurs par défaut du sujet", parDefaut: true };
  }
  try {
    return { casLimites: analyserValeurs(texte), source: `VALEURS_TESTS="${texte}"`, parDefaut: false };
  } catch (e) {
    return {
      casLimites: parDefaut,
      source: "valeurs par défaut du sujet (VALEURS_TESTS invalide)",
      parDefaut: true,
      erreur: `VALEURS_TESTS invalide : ${e.message}`,
    };
  }
}

const CHARGEMENT_VALEURS = chargerCasLimites();
const CAS_LIMITES = Object.freeze(CHARGEMENT_VALEURS.casLimites);

const NOMS_CAS = Object.keys(CAS_LIMITES);
const PARAMETRES = ["a", "b", "c"];

// ============================================================================
// 3. GÉNÉRATEUR COMBINATOIRE ALL-PAIRS GLOUTON SUR LES NATURES (sans jenny.exe)
// ============================================================================

/**
 * CLASSES DE DONNÉES
 *
 * Chaque cas limite est décrit par deux attributs, recalculés à partir de sa
 * valeur (donc valables pour n'importe quel dictionnaire VALEURS_TESTS) :
 *   - sa NATURE (ordre de grandeur, voir natureDe) : GRAND, NOMINAL,
 *     PROCHE_ZERO, TRES_PROCHE_ZERO ou NUL (5 classes au plus) ;
 *   - son SIGNE : NEGATIF, NUL ou POSITIF (3 classes au plus).
 *
 * Ce sont ces deux attributs qui pilotent la stabilité numérique :
 *   - le rapport des ORDRES DE GRANDEUR (ex. b ≫ a et c) provoque la
 *     cancellation catastrophique de la formule classique (-b ± √Δ) / 2a ;
 *   - le SIGNE du produit a·c décide du signe de Δ = b² − 4ac (réel / complexe).
 */
const NATURES = NOMS_CAS.map((nom) => natureDe(CAS_LIMITES[nom]));
const SIGNES = NOMS_CAS.map((nom) => signeDe(CAS_LIMITES[nom]));

/**
 * EXIGENCES DE COUVERTURE (ce que la matrice doit garantir à 100 %)
 *
 *   (E1) Paires de NATURES : pour chaque couple de paramètres (a,b), (b,c), (a,c),
 *        chacune des 5 × 5 = 25 combinaisons de natures se croise au moins une fois.
 *   (E2) Valeur × signe : chacune des 9 valeurs exactes de chaque paramètre
 *        rencontre au moins une fois un partenaire NÉGATIF, NUL et POSITIF
 *        dans chacun des deux autres paramètres.
 *
 * Remarque : couvrir les 81 paires de VALEURS exactes exigerait au moins 81 lignes
 * (une ligne ne contient qu'une seule paire (a,b)). Le passage par les classes
 * est ce qui permet de descendre à une quarantaine de cas.
 *
 * @param {number[]} ligne  indices des valeurs, ex. [0, 4, 8]
 * @returns {string[]}      clés des exigences couvertes par cette ligne
 */
function exigencesCouvertes(ligne) {
  const cles = [];
  for (let p = 0; p < ligne.length; p++) {
    for (let q = 0; q < ligne.length; q++) {
      if (p === q) continue;
      if (p < q) cles.push(`E1 ${PARAMETRES[p]}=${NATURES[ligne[p]]} / ${PARAMETRES[q]}=${NATURES[ligne[q]]}`);
      cles.push(`E2 ${PARAMETRES[p]}=${NOMS_CAS[ligne[p]]} / ${PARAMETRES[q]} ${SIGNES[ligne[q]]}`);
    }
  }
  return cles;
}

/** Produit cartésien exhaustif des indices : 9 × 9 × 9 = 729 lignes candidates. */
function produitCartesien(tailles) {
  return tailles.reduce(
    (lignes, taille) => lignes.flatMap((l) => Array.from({ length: taille }, (_, v) => [...l, v])),
    [[]],
  );
}

/**
 * Algorithme GLOUTON de couverture (famille AETG / IPOG) :
 *   tant qu'il reste des exigences non couvertes, on ajoute la ligne candidate
 *   qui en couvre le plus grand nombre (à égalité : la première rencontrée).
 *
 * Déterministe : la matrice est identique à chaque exécution, les verdicts
 * sont donc reproductibles.
 *
 * @param {number[]} tailles  nombre de valeurs de chaque paramètre, ex. [9, 9, 9]
 * @returns {number[][]}      lignes d'indices retenues
 */
function genererAllPairs(tailles) {
  const candidats = produitCartesien(tailles);
  const restantes = new Set(candidats.flatMap(exigencesCouvertes));
  const lignes = [];

  while (restantes.size > 0) {
    let meilleure = null;
    let meilleurGain = 0;
    for (const candidat of candidats) {
      const gain = exigencesCouvertes(candidat).filter((cle) => restantes.has(cle)).length;
      if (gain > meilleurGain) {
        meilleurGain = gain;
        meilleure = candidat;
      }
    }
    lignes.push(meilleure);
    for (const cle of exigencesCouvertes(meilleure)) restantes.delete(cle);
  }
  return lignes;
}

/** Exigences (E1 et E2) absentes de la matrice — doit être vide. */
function exigencesManquantes(lignes, tailles) {
  const couvertes = new Set(lignes.flatMap(exigencesCouvertes));
  const toutes = new Set(produitCartesien(tailles).flatMap(exigencesCouvertes));
  return [...toutes].filter((cle) => !couvertes.has(cle));
}

const TAILLES = PARAMETRES.map(() => NOMS_CAS.length); // [9, 9, 9]
const MATRICE_INDICES = genererAllPairs(TAILLES);

/** Matrice finale injectée dans test.each : un objet par cas. */
const MATRICE_ALL_PAIRS = MATRICE_INDICES.map((ligne, n) => {
  const [ia, ib, ic] = ligne;
  return {
    id: n + 1,
    a: CAS_LIMITES[NOMS_CAS[ia]],
    b: CAS_LIMITES[NOMS_CAS[ib]],
    c: CAS_LIMITES[NOMS_CAS[ic]],
    etiquettes: `${NOMS_CAS[ia]}, ${NOMS_CAS[ib]}, ${NOMS_CAS[ic]}`,
  };
});

// ============================================================================
// 4. ORACLE MATHÉMATIQUE INDÉPENDANT
// ============================================================================

/**
 * L'oracle NE recalcule PAS les racines avec une formule de résolution
 * (ce serait reproduire la logique du code testé). Il :
 *   1. calcule son propre discriminant Δ = b² − 4ac pour fixer la NATURE attendue ;
 *   2. vérifie les racines renvoyées par des propriétés mathématiques
 *      indépendantes de toute méthode de résolution :
 *        - relations de Viète : x1 + x2 = −b/a   et   x1 · x2 = c/a
 *        - résidu du 1er degré : b·x + c = 0
 *
 * Tolérance : epsilon machine (Number.EPSILON ≈ 2.22e-16), mise à l'échelle
 * par la taille des termes manipulés (borne d'erreur classique d'une opération
 * flottante). Une formule instable perd ~8 chiffres sur la petite racine quand
 * b ≫ a et c : le produit x1 · x2 s'écarte alors de c/a de ~1e-8 en relatif,
 * soit des millions de fois la tolérance → ÉCHEC immédiat.
 */
const ULP = 16; // nombre d'epsilon machine tolérés
const TOLERANCE = ULP * Number.EPSILON;

/**
 * |obtenu − attendu| ≤ TOLERANCE × échelle
 * `echelle` = grandeur des termes qui ont produit la valeur (pas la valeur
 * elle-même, qui peut valoir 0 après une soustraction exacte).
 */
function egalAEpsilonPres(obtenu, attendu, echelle) {
  if (!Number.isFinite(obtenu) || !Number.isFinite(attendu)) return false;
  return Math.abs(obtenu - attendu) <= TOLERANCE * echelle;
}

/** Nature attendue, déduite du seul discriminant calculé par l'oracle. */
function natureAttendue(a, b, c) {
  if (a === 0) {
    if (b === 0) return c === 0 ? "INFINITE_DE_SOLUTIONS" : "AUCUNE_SOLUTION";
    return "LINEAIRE";
  }
  const delta = b * b - 4 * a * c;
  if (delta > 0) return "DEUX_REELLES";
  if (delta === 0) return "RACINE_DOUBLE";
  return "COMPLEXES";
}

/** Vérifie la somme et le produit de deux racines (réelles ou complexes) par Viète. */
function verifierViete(a, b, c, somme, produit, echelleSomme, echelleProduit, erreurs) {
  const sommeAttendue = -b / a;
  const produitAttendu = c / a;
  if (!egalAEpsilonPres(somme, sommeAttendue, Math.max(echelleSomme, Math.abs(sommeAttendue)))) {
    erreurs.push(`Viète (somme) : x1 + x2 = ${somme}, attendu −b/a = ${sommeAttendue}`);
  }
  if (!egalAEpsilonPres(produit, produitAttendu, Math.max(echelleProduit, Math.abs(produitAttendu)))) {
    erreurs.push(`Viète (produit) : x1 · x2 = ${produit}, attendu c/a = ${produitAttendu}`);
  }
}

const estNombre = (x) => typeof x === "number" && Number.isFinite(x);

/**
 * Compare la réponse du solveur à l'oracle.
 * Renvoie la liste des écarts constatés (tableau vide = verdict RÉUSSI).
 */
function verifierAvecOracle(a, b, c, obtenu) {
  const nature = natureAttendue(a, b, c);
  const erreurs = [];

  // --- Structure générale ---
  if (obtenu === null || typeof obtenu !== "object") {
    return [`Le solveur doit renvoyer un objet, reçu : ${JSON.stringify(obtenu)}`];
  }
  if (obtenu.nature !== nature) {
    return [`Nature : attendu "${nature}", obtenu "${obtenu.nature}"`];
  }
  if (!Array.isArray(obtenu.solutions)) {
    return [`"solutions" doit être un tableau, reçu : ${JSON.stringify(obtenu.solutions)}`];
  }
  const s = obtenu.solutions;

  // --- Discriminant (uniquement pour a ≠ 0) ---
  if (a !== 0) {
    const delta = b * b - 4 * a * c;
    if (!egalAEpsilonPres(obtenu.delta, delta, b * b + Math.abs(4 * a * c))) {
      erreurs.push(`Δ : attendu ${delta}, obtenu ${obtenu.delta}`);
    }
  }

  // --- Solutions selon la nature ---
  switch (nature) {
    case "DEUX_REELLES": {
      if (s.length !== 2 || !s.every(estNombre)) {
        erreurs.push(`2 solutions réelles attendues, obtenu ${JSON.stringify(s)}`);
        break;
      }
      const [x1, x2] = s;
      verifierViete(a, b, c, x1 + x2, x1 * x2, Math.abs(x1) + Math.abs(x2), Math.abs(x1 * x2), erreurs);
      break;
    }
    case "RACINE_DOUBLE": {
      if (s.length !== 1 || !estNombre(s[0])) {
        erreurs.push(`1 racine double attendue, obtenu ${JSON.stringify(s)}`);
        break;
      }
      const [x0] = s;
      verifierViete(a, b, c, 2 * x0, x0 * x0, 2 * Math.abs(x0), x0 * x0, erreurs);
      break;
    }
    case "COMPLEXES": {
      const valides = s.length === 2 && s.every((z) => z && estNombre(z.re) && estNombre(z.im));
      if (!valides) {
        erreurs.push(`2 solutions complexes {re, im} attendues, obtenu ${JSON.stringify(s)}`);
        break;
      }
      const [z1, z2] = s;
      if (z1.re !== z2.re || z1.im !== -z2.im || z1.im === 0) {
        erreurs.push(`Les solutions complexes doivent être conjuguées (re ± i·im, im ≠ 0) : ${JSON.stringify(s)}`);
      }
      // z1 + z2 = 2·re ; z1 · z2 = re² + im² (produit de deux conjugués)
      const module2 = z1.re * z1.re + z1.im * z1.im;
      verifierViete(a, b, c, 2 * z1.re, module2, 2 * Math.abs(z1.re), module2, erreurs);
      if (typeof obtenu.texte !== "string" || !/\di\b/.test(obtenu.texte)) {
        erreurs.push(`Le texte des solutions complexes doit contenir la partie imaginaire « i », obtenu : ${obtenu.texte}`);
      }
      break;
    }
    case "LINEAIRE": {
      if (s.length !== 1 || !estNombre(s[0])) {
        erreurs.push(`1 solution attendue pour b·x + c = 0, obtenu ${JSON.stringify(s)}`);
        break;
      }
      // Résidu : on remplace x dans l'équation au lieu de recalculer −c/b.
      const [x] = s;
      if (!egalAEpsilonPres(b * x + c, 0, Math.abs(b * x) + Math.abs(c))) {
        erreurs.push(`Résidu : b·x + c = ${b * x + c} pour x = ${x}, attendu 0`);
      }
      break;
    }
    case "AUCUNE_SOLUTION":
    case "INFINITE_DE_SOLUTIONS":
      if (s.length !== 0) {
        erreurs.push(`Aucune solution numérique attendue, obtenu ${JSON.stringify(s)}`);
      }
      break;
  }
  return erreurs;
}

// ============================================================================
// 5. TESTS PARAMÉTRÉS (une bulle verte / rouge par combinaison dans l'IDE)
// ============================================================================

const SOLVEUR = chargerSolveurDynamiquement();
const EXHAUSTIF = NOMS_CAS.length ** PARAMETRES.length; // 729

describe(`Valeurs de test dynamiques — ${NOMS_CAS.length} valeurs (${CHARGEMENT_VALEURS.source})`, () => {
  test(`dictionnaire chargé : ${NOMS_CAS.map((n) => `${n}=${CAS_LIMITES[n]}`).join(", ")}`, () => {
    if (CHARGEMENT_VALEURS.erreur) throw new Error(`ÉCHOUÉ — ${CHARGEMENT_VALEURS.erreur}`);
    expect(NOMS_CAS.length).toBeGreaterThanOrEqual(2);
  });
});

describe("Générateur All-Pairs (glouton sur les natures)", () => {
  test(`réduit les ${EXHAUSTIF} combinaisons exhaustives à ${MATRICE_ALL_PAIRS.length} cas`, () => {
    expect(MATRICE_ALL_PAIRS.length).toBeLessThan(EXHAUSTIF);
  });

  // L'objectif « une quarantaine de cas » concerne les 9 états limites du sujet.
  (CHARGEMENT_VALEURS.parDefaut ? test : test.skip)(
    "avec les 9 valeurs du sujet : une quarantaine de cas (40 à 45)",
    () => {
      expect(MATRICE_ALL_PAIRS.length).toBeGreaterThanOrEqual(40);
      expect(MATRICE_ALL_PAIRS.length).toBeLessThanOrEqual(45);
    },
  );

  test("couvre 100 % des paires de natures (a,b), (b,c), (a,c) et des couples valeur × signe", () => {
    expect(exigencesManquantes(MATRICE_INDICES, TAILLES)).toEqual([]);
  });

  test(`utilise chacune des ${NOMS_CAS.length} valeurs limites pour a, b et c`, () => {
    for (let p = 0; p < PARAMETRES.length; p++) {
      const valeurs = new Set(MATRICE_INDICES.map((ligne) => ligne[p]));
      expect(valeurs.size).toBe(NOMS_CAS.length);
    }
  });
});

describe("Chargement dynamique du solveur", () => {
  test("le module de calcul est trouvé et expose une fonction de résolution", () => {
    if (SOLVEUR.erreur) throw new Error(`ÉCHOUÉ — ${SOLVEUR.erreur}`);
    expect(typeof SOLVEUR.fonction).toBe("function");
  });
});

describe("Solveur ax² + bx + c = 0 — matrice All-Pairs × oracle", () => {
  test.each(MATRICE_ALL_PAIRS)(
    "Cas $id : a=$a, b=$b, c=$c  [$etiquettes]",
    ({ a, b, c }) => {
      // Solveur absent : verdict ÉCHOUÉ explicite, les autres cas continuent.
      if (SOLVEUR.erreur) throw new Error(`ÉCHOUÉ — ${SOLVEUR.erreur}`);

      let obtenu;
      try {
        obtenu = SOLVEUR.fonction(a, b, c);
      } catch (e) {
        throw new Error(`ÉCHOUÉ — le solveur a levé une exception pour (${a}, ${b}, ${c}) : ${e.message}`);
      }

      expect(verifierAvecOracle(a, b, c, obtenu)).toEqual([]);
    },
  );
});
