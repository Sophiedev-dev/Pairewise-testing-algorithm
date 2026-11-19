"use strict";

/**
 * Solveur d'équations du second degré : a·x² + b·x + c = 0.
 *
 * Ce fichier est le « code testé ». La suite de tests (tests/solveur.test.js)
 * ne le référence jamais de façon statique : elle le charge dynamiquement.
 *
 * Contrat de retour (vérifié par l'oracle de la suite de tests) :
 *   { nature, delta, solutions, texte }
 *
 *   nature                   | delta  | solutions
 *   -------------------------|--------|---------------------------------------
 *   "DEUX_REELLES"           | Δ > 0  | [x1, x2]           (nombres)
 *   "RACINE_DOUBLE"          | Δ = 0  | [x0]               (nombre)
 *   "COMPLEXES"              | Δ < 0  | [{re, im}, {re, -im}]  + texte avec « i »
 *   "LINEAIRE"               | null   | [x]                (a = 0, b ≠ 0)
 *   "AUCUNE_SOLUTION"        | null   | []                 (a = 0, b = 0, c ≠ 0)
 *   "INFINITE_DE_SOLUTIONS"  | null   | []                 (a = b = c = 0)
 */
function resoudre(a, b, c) {
  // ---- Cas a = 0 : ce n'est plus une équation du second degré ----
  if (a === 0) {
    if (b === 0) {
      return c === 0
        ? { nature: "INFINITE_DE_SOLUTIONS", delta: null, solutions: [], texte: "0 = 0 : infinité de solutions" }
        : { nature: "AUCUNE_SOLUTION", delta: null, solutions: [], texte: `${c} = 0 : impossible` };
    }
    const x = -c / b;
    return { nature: "LINEAIRE", delta: null, solutions: [x], texte: `x = ${x}` };
  }

  // ---- Cas a ≠ 0 : discriminant ----
  const delta = b * b - 4 * a * c;

  if (delta > 0) {
    // Formule numériquement stable : on évite la soustraction de deux nombres
    // presque égaux (-b + √Δ quand b² ≫ 4ac), qui ferait perdre tous les chiffres.
    const signeB = b >= 0 ? 1 : -1;
    const q = -0.5 * (b + signeB * Math.sqrt(delta));
    const x1 = q / a;
    const x2 = c / q;
    return { nature: "DEUX_REELLES", delta, solutions: [x1, x2], texte: `x1 = ${x1}, x2 = ${x2}` };
  }

  if (delta === 0) {
    const x0 = -b / (2 * a);
    return { nature: "RACINE_DOUBLE", delta, solutions: [x0], texte: `x0 = ${x0}` };
  }

  const re = -b / (2 * a);
  const im = Math.sqrt(-delta) / (2 * Math.abs(a));
  return {
    nature: "COMPLEXES",
    delta,
    solutions: [{ re, im }, { re, im: -im }],
    texte: `x = ${re} ± ${im}i`,
  };
}

module.exports = { resoudre };
