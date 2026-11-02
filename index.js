"use strict";

const { execFile } = require("child_process");
const path = require("path");
const readline = require("readline");

const JENNY = path.join(__dirname, "jenny.exe");
// Dimension 1 -> a, 2 -> b, 3 -> c. Feature letter -> numeric value.
const COEFFICIENTS = ["a", "b", "c"];
// Fixed values for the first features; beyond that, random integers are generated.
const BASE_VALUES = [0, 5, -5, 1, -2];
// Feature letters in jenny's order: a..z then A..Z.
const JENNY_LETTERS =
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
// Hard limits of jenny.exe itself (it refuses fewer than 2 or more than 52 features).
const MIN_FEATURES = 2;
const MAX_FEATURES = JENNY_LETTERS.length;

// Tests imposed to jenny (read on stdin with -o), then extended to cover all pairs.
// x^2 - 2x + 1 = 0 guarantees a Δ = 0 case (only possible with >= 4/5/4 choices).
const REQUIRED_TESTS = [{ a: 1, b: -2, c: 1 }];

// Rebuilt from the user's input in run() by buildFeatureValues().
let FEATURE_VALUES = {};

function randomInt(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}

// n letter -> value pairs: BASE_VALUES first, then distinct random integers
// (positive or negative), e.g. 3 -> { a: 0, b: 5, c: -5 }.
// Values must stay unique so a value maps back to a single letter.
function buildFeatureValues(n) {
  const values = BASE_VALUES.slice(0, n);
  const range = Math.max(10, 2 * n);
  while (values.length < n) {
    const v = randomInt(-range, range);
    if (!values.includes(v)) values.push(v);
  }
  return Object.fromEntries(values.map((v, i) => [JENNY_LETTERS[i], v]));
}

// Explain why a token is not a positive integer.
function describeInvalidToken(tok) {
  if (/\p{L}/u.test(tok)) {
    return `"${tok}" contient des lettres. Veuillez entrer un nombre.`;
  }
  if (/^-\d+([.,]\d+)?$/.test(tok)) {
    return `"${tok}" est un nombre négatif. Veuillez entrer un nombre entier positif.`;
  }
  if (/^\d*[.,]\d+$/.test(tok)) {
    return `"${tok}" est un nombre décimal. Veuillez entrer un nombre entier.`;
  }
  return `"${tok}" contient un symbole. Veuillez entrer un nombre.`;
}

// Validate a line like "8 8 8" (or "8" as shorthand for "8 8 8").
// Returns { dims } on success, { error } with a message to show otherwise.
function parseDimensions(input) {
  const tokens = input.trim().split(/\s+/).filter(Boolean);
  if (tokens.length === 0) {
    return { error: "Vous n'avez rien saisi. Veuillez entrer un nombre." };
  }
  for (const tok of tokens) {
    if (!/^\d+$/.test(tok)) return { error: describeInvalidToken(tok) };
  }
  if (tokens.length === 1) tokens.push(tokens[0], tokens[0]);
  if (tokens.length !== COEFFICIENTS.length) {
    return {
      error: `Il faut ${COEFFICIENTS.length} nombres (un par coefficient ${COEFFICIENTS.join(", ")}), vous en avez saisi ${tokens.length}.`,
    };
  }
  const dims = tokens.map(Number);
  const tooSmall = dims.find((d) => d < MIN_FEATURES);
  if (tooSmall !== undefined) {
    return {
      error: `${tooSmall} est trop petit : jenny exige au moins ${MIN_FEATURES} choix par coefficient.`,
    };
  }
  const tooBig = tokens.find((t, i) => dims[i] > MAX_FEATURES);
  if (tooBig !== undefined) {
    return {
      error: `${tooBig} est trop grand : jenny.exe accepte au maximum ${MAX_FEATURES} choix par coefficient (lettres a-z puis A-Z).`,
    };
  }
  return { dims };
}

const fmt = (x) => {
  const r = Math.round(x * 1e4) / 1e4;
  return Object.is(r, -0) ? "0" : String(r);
};

// Solve a*x^2 + b*x + c = 0.
function solveQuadratic(a, b, c) {
  if (a === 0) {
    if (b === 0) {
      return c === 0
        ? { kind: "infinite", text: "Infinité de solutions (0 = 0)" }
        : { kind: "none", text: `Aucune solution (${fmt(c)} = 0 impossible)` };
    }
    const x = -c / b;
    return {
      kind: "linear",
      roots: [x],
      text: `Équation du 1er degré : x = ${fmt(x)}`,
    };
  }

  const delta = b * b - 4 * a * c;
  if (delta > 0) {
    const s = Math.sqrt(delta);
    const x1 = (-b - s) / (2 * a);
    const x2 = (-b + s) / (2 * a);
    return {
      kind: "two",
      delta,
      roots: [x1, x2],
      text: `Δ=${fmt(delta)} > 0 : x1 = ${fmt(x1)}, x2 = ${fmt(x2)}`,
    };
  }
  if (delta === 0) {
    const x = -b / (2 * a);
    return {
      kind: "double",
      delta,
      roots: [x],
      text: `Δ=0 : racine double x = ${fmt(x)}`,
    };
  }
  const re = -b / (2 * a);
  const im = Math.sqrt(-delta) / (2 * Math.abs(a));
  return {
    kind: "complex",
    delta,
    text: `Δ=${fmt(delta)} < 0 : x = ${fmt(re)} ± ${fmt(im)}i`,
  };
}

// Parse jenny output: one test per line, tokens like "1a 2b 3c".
// jenny reports its own errors on stdout ("jenny: ...") with exit code 0.
function parseJenny(stdout) {
  const lines = stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("Could not cover"));
  const failure = lines.find((line) => line.startsWith("jenny:"));
  if (failure) throw new Error(failure);
  return lines.map((line) => line.split(/\s+/));
}

function tokensToCoefficients(tokens) {
  const coeffs = {};
  for (const tok of tokens) {
    const m = /^(\d+)([a-zA-Z])$/.exec(tok);
    if (!m) throw new Error(`Jeton jenny invalide : "${tok}"`);
    const name = COEFFICIENTS[Number(m[1]) - 1];
    const value = FEATURE_VALUES[m[2]];
    if (name === undefined || value === undefined) {
      throw new Error(`Jeton jenny non pris en charge : "${tok}"`);
    }
    coeffs[name] = value;
  }
  for (const name of COEFFICIENTS) {
    if (!(name in coeffs))
      throw new Error(
        `Coefficient ${name} manquant dans "${tokens.join(" ")}"`,
      );
  }
  return coeffs;
}

// Inverse of tokensToCoefficients: { a: 1, b: -2, c: 1 } -> " 1d 2e 3d ".
function coefficientsToTestLine(coeffs) {
  const tokens = COEFFICIENTS.map((name, i) => {
    const letter = Object.keys(FEATURE_VALUES).find(
      (l) => FEATURE_VALUES[l] === coeffs[name],
    );
    if (letter === undefined)
      throw new Error(`Valeur ${coeffs[name]} absente de FEATURE_VALUES`);
    return `${i + 1}${letter}`;
  });
  return ` ${tokens.join(" ")} \n`;
}

// A required test can only be passed to jenny if each value exists in its dimension
// (dimension i uses the first dims[i] entries of FEATURE_VALUES).
function fitsDimensions(coeffs, dims) {
  const values = Object.values(FEATURE_VALUES);
  return COEFFICIENTS.every((name, i) =>
    values.slice(0, dims[i]).includes(coeffs[name]),
  );
}

function printTable(rows) {
  const headers = ["ID", "Jetons Jenny", "a", "b", "c", "Résultat"];
  const widths = headers.map((h, i) =>
    Math.max(h.length, ...rows.map((r) => String(r[i]).length)),
  );
  const line = (l, m, r) =>
    l + widths.map((w) => "─".repeat(w + 2)).join(m) + r;
  const row = (cells) =>
    "│" +
    cells
      .map((cell, i) => {
        const s = String(cell);
        // Right-align numeric columns.
        return (
          " " +
          (i >= 2 && i <= 4 ? s.padStart(widths[i]) : s.padEnd(widths[i])) +
          " "
        );
      })
      .join("│") +
    "│";

  console.log(line("┌", "┬", "┐"));
  console.log(row(headers));
  console.log(line("├", "┼", "┤"));
  rows.forEach((r) => console.log(row(r)));
  console.log(line("└", "┴", "┘"));
}

function run(dims, done) {
  FEATURE_VALUES = buildFeatureValues(Math.max(...dims));
  const jennyArgs = ["-o", ...dims.map(String)];
  const required = REQUIRED_TESTS.filter((t) => fitsDimensions(t, dims));

  const child = execFile(
    JENNY,
    jennyArgs,
    { cwd: __dirname, windowsHide: true, maxBuffer: 16 * 1024 * 1024 },
    (err, stdout, stderr) => {
      if (err) {
        console.error(
          `Échec de l'exécution de ${JENNY} ${jennyArgs.join(" ")} : ${err.message}`,
        );
        if (stderr) console.error(stderr);
        return done(err);
      }

      let rows;
      try {
        rows = parseJenny(stdout).map((tokens, i) => {
          const { a, b, c } = tokensToCoefficients(tokens);
          return [
            i + 1,
            tokens.join(" "),
            a,
            b,
            c,
            solveQuadratic(a, b, c).text,
          ];
        });
      } catch (e) {
        console.error(`Erreur : ${e.message}`);
        return done(e);
      }

      const mapping = Object.entries(FEATURE_VALUES)
        .map(([l, v]) => `${l}=${v}`)
        .join(", ");
      console.log(
        `\njenny ${jennyArgs.join(" ")} → ${rows.length} tests (${mapping})\n`,
      );
      printTable(rows);
      done(null);
    },
  );

  // jenny -o without a file name reads old tests on stdin until a "." line.
  child.stdin.end(required.map(coefficientsToTestLine).join("") + ".\n");
}

function main() {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  // Ctrl+C / Ctrl+D before a valid answer: quit. While jenny runs, let it finish.
  let running = false;
  rl.on("close", () => {
    if (!running) process.exit(process.exitCode || 0);
  });

  const ask = () => {
    rl.question(
      "Entrez les dimensions pour Jenny (ex: 3 3 3, 5 5 5 ou 8 8 8) : ",
      (answer) => {
        const { dims, error } = parseDimensions(answer);
        if (error) {
          console.log(`  ✗ ${error}\n`);
          return ask();
        }
        running = true;
        run(dims, (err) => {
          if (err) process.exitCode = 1;
          rl.close();
        });
      },
    );
  };
  ask();
}

if (require.main === module) main();

module.exports = {
  parseDimensions,
  buildFeatureValues,
  solveQuadratic,
  parseJenny,
  tokensToCoefficients,
  coefficientsToTestLine,
  printTable,
};
