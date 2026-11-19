"use strict";

/**
 * Lanceur interactif de la suite de tests.
 *
 *   npm start
 *
 * Demande au clavier les valeurs limites à injecter dans a, b et c, puis lance
 * Jest en les transmettant à tests/solveur.test.js via VALEURS_TESTS.
 * Entrée vide → les 9 valeurs limites du sujet.
 */

const { spawn } = require("child_process");
const readline = require("readline");

const QUESTION =
  "Entrez les valeurs de test séparées par des espaces ou des virgules\n" +
  "(ex: -100000 -5 -0.1 -0.000001 0 0.000001 0.1 5 100000)\n" +
  "Appuyez sur Entrée sans rien taper pour utiliser les 9 valeurs du sujet : ";

/** Vérifie la saisie ; renvoie { valeurs } ou { erreur }. */
function analyserSaisie(texte) {
  const morceaux = texte.trim().split(/[\s,;]+/).filter(Boolean);
  if (morceaux.length === 0) return { valeurs: [] }; // valeurs par défaut

  for (const m of morceaux) {
    if (!Number.isFinite(Number(m))) {
      return { erreur: `"${m}" n'est pas un nombre. Utilisez le point pour les décimales (ex: 0.1).` };
    }
  }
  const distinctes = [...new Set(morceaux.map((m) => Number(m) || 0))];
  if (distinctes.length < 2) {
    return { erreur: "Il faut au moins 2 valeurs différentes." };
  }
  return { valeurs: distinctes };
}

function lancerJest(valeurs) {
  const env = { ...process.env };
  if (valeurs.length > 0) env.VALEURS_TESTS = valeurs.join(",");
  else delete env.VALEURS_TESTS;

  console.log(
    valeurs.length > 0
      ? `\nTests lancés avec ${valeurs.length} valeurs : ${valeurs.join(", ")}\n`
      : "\nTests lancés avec les 9 valeurs du sujet.\n",
  );

  const jest = spawn(process.execPath, [require.resolve("jest/bin/jest"), "--verbose"], {
    cwd: __dirname,
    env,
    stdio: "inherit",
  });
  jest.on("exit", (code) => process.exit(code ?? 1));
}

function main() {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  let termine = false;
  rl.on("close", () => {
    if (!termine) process.exit(0); // Ctrl+C / Ctrl+D avant une saisie valide
  });

  const demander = () => {
    rl.question(QUESTION, (reponse) => {
      const { valeurs, erreur } = analyserSaisie(reponse);
      if (erreur) {
        console.log(`  ✗ ${erreur}\n`);
        return demander();
      }
      termine = true;
      rl.close();
      lancerJest(valeurs);
    });
  };
  demander();
}

main();
