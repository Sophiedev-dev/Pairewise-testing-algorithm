package quadratique;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.pavelicii.allpairs4j.AllPairs;
import io.github.pavelicii.allpairs4j.Case;
import io.github.pavelicii.allpairs4j.Parameter;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ============================================================================
 *  HARNAIS DE TEST V&V — Solveur d'équations du second degré  a·x² + b·x + c = 0
 *  Framework : JUnit 5 (Jupiter)   —   Génération des données : allpairs4j
 * ============================================================================
 *
 *  Organisation :
 *    1. Découplage absolu & chargement dynamique (Java Reflection API)
 *    2. Les 9 CATÉGORIES de valeurs (classes d'équivalence) et leurs valeurs
 *    3. Générateur combinatoire Pairwise sur les catégories : allpairs4j
 *    4. Oracle mathématique indépendant (relations de Viète + epsilon machine)
 *    5. Tests paramétrés (@ParameterizedTest + @MethodSource → une bulle par cas)
 *
 *  Ce fichier n'importe PAS la classe Solveur : il compile et s'exécute même si
 *  elle n'existe pas (TDD). Chaque cas est alors ÉCHOUÉ avec un message explicite.
 *
 *  Lancement depuis l'IDE (▶) : une fenêtre propose une valeur pour chacune des
 *  9 catégories ; on peut les modifier avant de lancer les tests.
 *
 *  Paramètres optionnels (propriété système -D… ou variable d'environnement) :
 *    -Dvaleurs.demander=false                   ne pas ouvrir la fenêtre
 *    -Dgraine=42                                rejouer le même tirage de valeurs
 *    -Dsolveur.classe=quadratique.SolveurNaif   tester une autre implémentation
 * ============================================================================
 */
@DisplayName("Solveur ax² + bx + c = 0 — JUnit 5 + allpairs4j")
class SolveurTest {

    // ========================================================================
    // 1. DÉCOUPLAGE ABSOLU & CHARGEMENT DYNAMIQUE (RÉFLEXIVITÉ)
    // ========================================================================

    /** Nom SUPPOSÉ de la classe à tester : une simple chaîne, aucun import. */
    static final String NOM_CLASSE = lireParametre("solveur.classe", "SOLVEUR_CLASSE", "quadratique.Solveur");

    /** Noms de méthode acceptés, de signature (double, double, double). */
    static final String[] NOMS_METHODE = {"resoudre", "resoudreEquation", "solve", "solveQuadratic"};

    /** Résultat du chargement : soit une méthode appelable, soit un message d'erreur. */
    static final class Chargement {
        final Method methode;
        final Object instance; // null si la méthode est statique
        final String erreur;

        Chargement(Method methode, Object instance, String erreur) {
            this.methode = methode;
            this.instance = instance;
            this.erreur = erreur;
        }
    }

    /**
     * Java Reflection API, en 3 étapes, sans jamais nommer la classe concrète :
     *   1. Class.forName(NOM_CLASSE)                 → chargement dynamique ;
     *   2. getMethod("resoudre", double × 3)         → découverte de la méthode ;
     *   3. getDeclaredConstructor().newInstance()    → instanciation dynamique
     *      (sauf si la méthode trouvée est statique).
     * Ne lève jamais d'exception : toute erreur (classe absente, méthode absente,
     * constructeur inaccessible…) devient un message.
     */
    static Chargement chargerSolveur() {
        try {
            Class<?> classe = Class.forName(NOM_CLASSE);
            for (String nom : NOMS_METHODE) {
                try {
                    Method m = classe.getMethod(nom, double.class, double.class, double.class);
                    Object instance = Modifier.isStatic(m.getModifiers())
                        ? null
                        : classe.getDeclaredConstructor().newInstance();
                    return new Chargement(m, instance, null);
                } catch (NoSuchMethodException ignoree) {
                    // on essaie le nom suivant
                }
            }
            return new Chargement(null, null, "La classe \"" + NOM_CLASSE + "\" existe, mais n'expose aucune "
                + "méthode (double, double, double) parmi : " + String.join(", ", NOMS_METHODE) + ".");
        } catch (ClassNotFoundException e) {
            return new Chargement(null, null,
                "Module de calcul introuvable : la classe \"" + NOM_CLASSE + "\" n'existe pas.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return new Chargement(null, null, "Module de calcul invalide (\"" + NOM_CLASSE + "\") : " + e);
        }
    }

    static final Chargement SOLVEUR = chargerSolveur();

    // ========================================================================
    // 2. LES 9 CATÉGORIES DE VALEURS (CLASSES D'ÉQUIVALENCE, IEEE 754)
    // ========================================================================

    /**
     * Une catégorie = un intervalle de valeurs qui doivent se comporter de la même
     * façon (classe d'équivalence). Les nombres du sujet (±100000, ±5, ±0.1,
     * ±0.000001, 0) ne sont que des EXEMPLES de chaque catégorie : n'importe quelle
     * valeur de l'intervalle convient.
     *
     *   Catégorie                  Intervalle               Exemple du sujet
     *   GRAND_NEGATIF              x ≤ -1000                -100000
     *   NOMINAL_NEGATIF            -1000 < x ≤ -1           -5
     *   PROCHE_ZERO_NEGATIF        -1 < x ≤ -0.001          -0.1
     *   TRES_PROCHE_ZERO_NEGATIF   -0.001 < x < 0           -0.000001
     *   NUL                        x = 0                    0
     *   TRES_PROCHE_ZERO_POSITIF   0 < x < 0.001            0.000001
     *   PROCHE_ZERO_POSITIF        0.001 ≤ x < 1            0.1
     *   NOMINAL_POSITIF            1 ≤ x < 1000             5
     *   GRAND_POSITIF              x ≥ 1000                 100000
     */
    static final class Categorie {
        final String nom;
        final int signe;               // -1, 0 ou +1
        final double min;              // |x| ≥ min
        final double max;              // |x| < max
        final double minTirage;        // bornes de |x| pour le tirage au hasard
        final double maxTirage;
        final double exemple;          // valeur donnée dans le sujet

        Categorie(String nom, int signe, double min, double max, double minTirage, double maxTirage, double exemple) {
            this.nom = nom;
            this.signe = signe;
            this.min = min;
            this.max = max;
            this.minTirage = minTirage;
            this.maxTirage = maxTirage;
            this.exemple = exemple;
        }

        boolean contient(double x) {
            if (signe == 0) return x == 0;
            if (Math.signum(x) != signe) return false;
            double m = Math.abs(x);
            return m >= min && m < max;
        }

        /** Valeur au hasard dans la catégorie (échelle logarithmique, 3 chiffres significatifs). */
        double tirer(Random hasard) {
            if (signe == 0) return 0.0;
            while (true) {
                double exposant = Math.log10(minTirage)
                    + hasard.nextDouble() * (Math.log10(maxTirage) - Math.log10(minTirage));
                double x = signe * new BigDecimal(Math.pow(10, exposant)).round(new MathContext(3)).doubleValue();
                if (contient(x)) return x;
            }
        }

        String intervalle() {
            String bas = format(min);
            String haut = format(max);
            if (signe == 0) return "x = 0";
            if (signe > 0) {
                if (min == 0) return "0 < x < " + haut;
                if (Double.isInfinite(max)) return "x ≥ " + bas;
                return bas + " ≤ x < " + haut;
            }
            if (Double.isInfinite(max)) return "x ≤ -" + bas;
            if (min == 0) return "-" + haut + " < x < 0";
            return "-" + haut + " < x ≤ -" + bas;
        }
    }

    static final double INFINI = Double.POSITIVE_INFINITY;

    static final List<Categorie> CATEGORIES = Arrays.asList(
        new Categorie("GRAND_NEGATIF", -1, 1e3, INFINI, 1e3, 1e6, -100000.0),
        new Categorie("NOMINAL_NEGATIF", -1, 1, 1e3, 1, 1e3, -5.0),
        new Categorie("PROCHE_ZERO_NEGATIF", -1, 1e-3, 1, 1e-3, 1, -0.1),
        new Categorie("TRES_PROCHE_ZERO_NEGATIF", -1, 0, 1e-3, 1e-9, 1e-3, -0.000001),
        new Categorie("NUL", 0, 0, 0, 0, 0, 0.0),
        new Categorie("TRES_PROCHE_ZERO_POSITIF", 1, 0, 1e-3, 1e-9, 1e-3, 0.000001),
        new Categorie("PROCHE_ZERO_POSITIF", 1, 1e-3, 1, 1e-3, 1, 0.1),
        new Categorie("NOMINAL_POSITIF", 1, 1, 1e3, 1, 1e3, 5.0),
        new Categorie("GRAND_POSITIF", 1, 1e3, INFINI, 1e3, 1e6, 100000.0));

    static final List<String> NOMS_CATEGORIES =
        CATEGORIES.stream().map(c -> c.nom).collect(Collectors.toList());

    /**
     * Graine du tirage au hasard : affichée dans les résultats, et réutilisable
     * avec -Dgraine=… pour rejouer exactement les mêmes valeurs.
     */
    static final long GRAINE = Long.parseLong(
        lireParametre("graine", "GRAINE", Long.toString(System.currentTimeMillis())));

    /** Valeur retenue pour chaque catégorie (même ordre que CATEGORIES). */
    static final double[] VALEURS = choisirValeurs();

    static double[] choisirValeurs() {
        Random hasard = new Random(GRAINE);
        double[] tirees = new double[CATEGORIES.size()];
        for (int i = 0; i < tirees.length; i++) tirees[i] = CATEGORIES.get(i).tirer(hasard);
        return demanderValeursParFenetre(tirees, hasard);
    }

    static double valeurDe(String categorie) {
        return VALEURS[NOMS_CATEGORIES.indexOf(categorie)];
    }

    /**
     * Fenêtre de saisie : une case par catégorie, pré-remplie avec une valeur tirée
     * au hasard. On peut taper sa propre valeur, reprendre les exemples du sujet
     * ou refaire un tirage. Chaque valeur doit appartenir à sa catégorie.
     *
     * Pourquoi une fenêtre et pas le clavier dans la console ? Quand JUnit est
     * lancé par l'IDE (▶) ou par Maven, les tests tournent dans un processus
     * séparé qui n'est pas relié au clavier : un Scanner(System.in) ne recevrait
     * rien. Une fenêtre Swing, elle, s'affiche toujours à l'écran.
     *
     * Pas de fenêtre (valeurs tirées gardées) si -Dvaleurs.demander=false ou sans écran.
     */
    static double[] demanderValeursParFenetre(double[] proposees, Random hasard) {
        if ("false".equalsIgnoreCase(lireParametre("valeurs.demander", "VALEURS_DEMANDER", "true"))
            || GraphicsEnvironment.isHeadless()) {
            return proposees;
        }

        JTextField[] champs = new JTextField[CATEGORIES.size()];
        JPanel grille = new JPanel(new GridLayout(0, 3, 10, 4));
        grille.add(new JLabel("<html><b>Catégorie</b></html>"));
        grille.add(new JLabel("<html><b>Intervalle</b></html>"));
        grille.add(new JLabel("<html><b>Valeur testée</b></html>"));
        for (int i = 0; i < champs.length; i++) {
            grille.add(new JLabel(CATEGORIES.get(i).nom));
            grille.add(new JLabel(CATEGORIES.get(i).intervalle()));
            champs[i] = new JTextField(format(proposees[i]), 12);
            grille.add(champs[i]);
        }
        JPanel contenu = new JPanel(new BorderLayout(0, 10));
        contenu.add(new JLabel("<html>allpairs4j combine les 9 catégories par paires pour a, b et c.<br>"
            + "Choisissez la valeur qui représente chaque catégorie :</html>"), BorderLayout.NORTH);
        contenu.add(grille, BorderLayout.CENTER);

        String[] boutons = {"Lancer les tests", "Exemples du sujet", "Nouveau tirage au hasard"};
        // Fenêtre parente invisible « toujours au premier plan » : la question
        // ne se cache pas derrière l'IDE.
        JFrame parent = new JFrame();
        parent.setAlwaysOnTop(true);
        parent.setLocationRelativeTo(null);
        try {
            while (true) {
                int choix = JOptionPane.showOptionDialog(parent, contenu,
                    "Valeurs de test — Solveur ax² + bx + c = 0", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, boutons, boutons[0]);
                if (choix < 0) return proposees; // fenêtre fermée : tirage gardé
                if (choix == 1 || choix == 2) {
                    for (int i = 0; i < champs.length; i++) {
                        Categorie cat = CATEGORIES.get(i);
                        champs[i].setText(format(choix == 1 ? cat.exemple : cat.tirer(hasard)));
                    }
                    continue;
                }
                try {
                    return lireChamps(champs);
                } catch (IllegalArgumentException e) {
                    JOptionPane.showMessageDialog(parent, e.getMessage(), "Valeur invalide", JOptionPane.ERROR_MESSAGE);
                }
            }
        } finally {
            parent.dispose();
        }
    }

    static double[] lireChamps(JTextField[] champs) {
        double[] valeurs = new double[champs.length];
        for (int i = 0; i < champs.length; i++) {
            Categorie cat = CATEGORIES.get(i);
            String texte = champs[i].getText().trim().replace(',', '.');
            try {
                valeurs[i] = Double.parseDouble(texte) + 0.0; // -0.0 → 0.0
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(cat.nom + " : \"" + texte + "\" n'est pas un nombre.");
            }
            if (!cat.contient(valeurs[i])) {
                throw new IllegalArgumentException(cat.nom + " : " + texte
                    + " n'appartient pas à la catégorie (" + cat.intervalle() + ").");
            }
        }
        return valeurs;
    }

    // ========================================================================
    // 3. GÉNÉRATEUR COMBINATOIRE PAIRWISE SUR LES CATÉGORIES : allpairs4j
    // ========================================================================

    /**
     * allpairs4j reçoit les 3 paramètres a, b, c, chacun avec les 9 CATÉGORIES,
     * et génère en mémoire un jeu de cas tel que chaque paire de catégories
     * (a,b), (b,c) et (a,c) apparaisse au moins une fois :
     * 9 × 9 × 9 = 729 combinaisons exhaustives → 85 cas.
     *
     * (Une ligne ne contient qu'une seule paire (a,b) ; avec 9 catégories il y a
     * 81 paires (a,b), donc au moins 81 cas, quel que soit l'algorithme.)
     */
    static final List<Case> CAS_GENERES = genererPairwise();

    static List<Case> genererPairwise() {
        return new AllPairs.AllPairsBuilder()
            .withParameter(new Parameter("a", NOMS_CATEGORIES))
            .withParameter(new Parameter("b", NOMS_CATEGORIES))
            .withParameter(new Parameter("c", NOMS_CATEGORIES))
            .build()
            .getGeneratedCases();
    }

    static final String[] PARAMETRES = {"a", "b", "c"};

    /** Source du test paramétré : (a, b, c, catégories) pour chaque cas allpairs4j. */
    static Stream<Arguments> matriceAllPairs() {
        return CAS_GENERES.stream().map(cas -> casDeTest(
            (String) cas.get("a"), (String) cas.get("b"), (String) cas.get("c"), ""));
    }

    /**
     * CAS IMPOSÉS, ajoutés en plus de la matrice allpairs4j.
     *
     * Pairwise garantit chaque PAIRE de catégories, pas chaque TRIPLET. Le rapport
     * JaCoCo a montré que la branche « infinité de solutions » du solveur n'était
     * jamais exécutée : elle exige le triplet (NUL, NUL, NUL), qu'aucun algorithme
     * par paires n'est obligé de produire. Idem pour la racine double (b = c = 0).
     * Ces triplets sont donc imposés (comme l'option -o de jenny).
     */
    static final String[][] CAS_IMPOSES = {
        {"NUL", "NUL", "NUL"},             // a = b = c = 0      → infinité de solutions
        {"NOMINAL_POSITIF", "NUL", "NUL"}, // a ≠ 0, b = c = 0   → Δ = 0, racine double
    };

    static Stream<Arguments> casImposes() {
        return Stream.concat(
            Arrays.stream(CAS_IMPOSES).map(t -> casDeTest(t[0], t[1], t[2], "cas imposé : ")),
            Stream.of(racineDoubleAvecBNonNul()));
    }

    /**
     * Racine double avec b ≠ 0, ajoutée après l'analyse PIT (tests de mutation).
     *
     * Le seul cas Δ = 0 ci-dessus a b = 0 : la racine x0 = −b/2a y vaut 0 quoi
     * qu'on fasse au calcul. PIT a ainsi montré que des mutants de cette ligne
     * (signe supprimé, × remplacé par ÷) SURVIVAIENT alors que JaCoCo la donnait
     * couverte à 100 % : la ligne était exécutée, mais son résultat jamais vérifié.
     *
     * Avec p la valeur de NOMINAL_POSITIF : a = c = p et b = 2p, soit
     * p·(x + 1)² = 0 → x0 = −1. Δ = (2p)² − 4·p·p vaut EXACTEMENT 0 en flottants
     * (multiplier par 2 ou 4 est exact en IEEE 754).
     */
    static Arguments racineDoubleAvecBNonNul() {
        double p = valeurDe("NOMINAL_POSITIF");
        return Arguments.of(p, 2 * p, p, "cas imposé : racine double p·(x + 1)² avec p = NOMINAL_POSITIF");
    }

    static Arguments casDeTest(String ca, String cb, String cc, String prefixe) {
        return Arguments.of(valeurDe(ca), valeurDe(cb), valeurDe(cc), prefixe + ca + ", " + cb + ", " + cc);
    }

    /** Vérification INDÉPENDANTE d'allpairs4j : paires de catégories absentes des cas générés. */
    static List<String> pairesManquantes() {
        Set<String> couvertes = new HashSet<>();
        for (Case cas : CAS_GENERES) {
            for (int i = 0; i < PARAMETRES.length; i++) {
                for (int j = i + 1; j < PARAMETRES.length; j++) {
                    couvertes.add(PARAMETRES[i] + "=" + cas.get(PARAMETRES[i]) + " / " + PARAMETRES[j] + "=" + cas.get(PARAMETRES[j]));
                }
            }
        }
        List<String> manquantes = new ArrayList<>();
        for (int i = 0; i < PARAMETRES.length; i++) {
            for (int j = i + 1; j < PARAMETRES.length; j++) {
                for (String ci : NOMS_CATEGORIES) {
                    for (String cj : NOMS_CATEGORIES) {
                        String paire = PARAMETRES[i] + "=" + ci + " / " + PARAMETRES[j] + "=" + cj;
                        if (!couvertes.contains(paire)) manquantes.add(paire);
                    }
                }
            }
        }
        return manquantes;
    }

    // ========================================================================
    // 4. ORACLE MATHÉMATIQUE INDÉPENDANT
    // ========================================================================

    /*
     * L'oracle NE recalcule PAS les racines avec une formule de résolution
     * (ce serait reproduire la logique du code testé). Il :
     *   1. calcule son propre discriminant Δ = b² − 4ac → NATURE attendue ;
     *   2. vérifie les racines renvoyées par des propriétés indépendantes :
     *        - relations de Viète : x1 + x2 = −b/a  et  x1 · x2 = c/a
     *        - résidu du 1er degré : b·x + c = 0
     *
     * Tolérance : epsilon machine (Math.ulp(1.0) ≈ 2.22e-16), mise à l'échelle
     * par la grandeur des termes calculés. Une formule instable perd ~8 chiffres
     * sur la petite racine quand b ≫ a et c : le produit x1 · x2 s'écarte alors
     * de c/a de ~1e-8 en relatif, des millions de fois la tolérance → ÉCHEC.
     */
    static final double EPSILON_MACHINE = Math.ulp(1.0);
    static final double TOLERANCE = 16 * EPSILON_MACHINE;

    static boolean egalAEpsilonPres(double obtenu, double attendu, double echelle) {
        if (Double.isNaN(obtenu) || Double.isInfinite(obtenu)) return false;
        return Math.abs(obtenu - attendu) <= TOLERANCE * echelle;
    }

    static String natureAttendue(double a, double b, double c) {
        if (a == 0) {
            if (b == 0) return c == 0 ? "INFINITE_DE_SOLUTIONS" : "AUCUNE_SOLUTION";
            return "LINEAIRE";
        }
        double delta = b * b - 4 * a * c;
        if (delta > 0) return "DEUX_REELLES";
        if (delta == 0) return "RACINE_DOUBLE";
        return "COMPLEXES";
    }

    static void verifierViete(double a, double b, double c, double somme, double produit,
                              double echelleSomme, double echelleProduit, List<String> erreurs) {
        double sommeAttendue = -b / a;
        double produitAttendu = c / a;
        if (!egalAEpsilonPres(somme, sommeAttendue, Math.max(echelleSomme, Math.abs(sommeAttendue)))) {
            erreurs.add("Viète (somme) : x1 + x2 = " + somme + ", attendu −b/a = " + sommeAttendue);
        }
        if (!egalAEpsilonPres(produit, produitAttendu, Math.max(echelleProduit, Math.abs(produitAttendu)))) {
            erreurs.add("Viète (produit) : x1 · x2 = " + produit + ", attendu c/a = " + produitAttendu);
        }
    }

    /** Compare la réponse du solveur à l'oracle. Liste vide = verdict RÉUSSI. */
    static List<String> verifierAvecOracle(double a, double b, double c, Object reponse) {
        List<String> erreurs = new ArrayList<>();
        String nature = natureAttendue(a, b, c);

        // --- Structure générale ---
        if (!(reponse instanceof Map)) {
            erreurs.add("Le solveur doit renvoyer une Map, reçu : " + reponse);
            return erreurs;
        }
        Map<?, ?> obtenu = (Map<?, ?>) reponse;
        if (!nature.equals(obtenu.get("nature"))) {
            erreurs.add("Nature : attendu \"" + nature + "\", obtenu \"" + obtenu.get("nature") + "\"");
            return erreurs;
        }
        if (!(obtenu.get("solutions") instanceof List)) {
            erreurs.add("\"solutions\" doit être une List, reçu : " + obtenu.get("solutions"));
            return erreurs;
        }
        List<?> s = (List<?>) obtenu.get("solutions");

        // --- Discriminant (uniquement pour a ≠ 0) ---
        if (a != 0) {
            double delta = b * b - 4 * a * c;
            Object deltaObtenu = obtenu.get("delta");
            if (!(deltaObtenu instanceof Number)
                || !egalAEpsilonPres(((Number) deltaObtenu).doubleValue(), delta, b * b + Math.abs(4 * a * c))) {
                erreurs.add("Δ : attendu " + delta + ", obtenu " + deltaObtenu);
            }
        }

        // --- Solutions selon la nature ---
        switch (nature) {
            case "DEUX_REELLES": {
                if (s.size() != 2 || !toutesReelles(s)) {
                    erreurs.add("2 solutions réelles attendues, obtenu " + s);
                    break;
                }
                double x1 = reel(s.get(0));
                double x2 = reel(s.get(1));
                verifierViete(a, b, c, x1 + x2, x1 * x2, Math.abs(x1) + Math.abs(x2), Math.abs(x1 * x2), erreurs);
                break;
            }
            case "RACINE_DOUBLE": {
                if (s.size() != 1 || !toutesReelles(s)) {
                    erreurs.add("1 racine double attendue, obtenu " + s);
                    break;
                }
                double x0 = reel(s.get(0));
                verifierViete(a, b, c, 2 * x0, x0 * x0, 2 * Math.abs(x0), x0 * x0, erreurs);
                break;
            }
            case "COMPLEXES": {
                if (s.size() != 2 || !(s.get(0) instanceof double[]) || !(s.get(1) instanceof double[])
                    || ((double[]) s.get(0)).length != 2 || ((double[]) s.get(1)).length != 2) {
                    erreurs.add("2 solutions complexes {re, im} (double[2]) attendues, obtenu " + s);
                    break;
                }
                double[] z1 = (double[]) s.get(0);
                double[] z2 = (double[]) s.get(1);
                if (z1[0] != z2[0] || z1[1] != -z2[1] || z1[1] == 0) {
                    erreurs.add("Les solutions complexes doivent être conjuguées (re ± i·im, im ≠ 0) : "
                        + Arrays.toString(z1) + " / " + Arrays.toString(z2));
                }
                // z1 + z2 = 2·re ; z1 · z2 = re² + im² (produit de deux conjugués)
                double module2 = z1[0] * z1[0] + z1[1] * z1[1];
                verifierViete(a, b, c, 2 * z1[0], module2, 2 * Math.abs(z1[0]), module2, erreurs);
                Object texte = obtenu.get("texte");
                if (!(texte instanceof String) || !((String) texte).matches(".*\\di\\b.*")) {
                    erreurs.add("Le texte des solutions complexes doit contenir la partie imaginaire « i », obtenu : " + texte);
                }
                break;
            }
            case "LINEAIRE": {
                if (s.size() != 1 || !toutesReelles(s)) {
                    erreurs.add("1 solution attendue pour b·x + c = 0, obtenu " + s);
                    break;
                }
                // Résidu : on remplace x dans l'équation au lieu de recalculer −c/b.
                double x = reel(s.get(0));
                double residu = b * x + c;
                if (!egalAEpsilonPres(residu, 0, Math.abs(b * x) + Math.abs(c))) {
                    erreurs.add("Résidu : b·x + c = " + residu + " pour x = " + x + ", attendu 0");
                }
                break;
            }
            default: // AUCUNE_SOLUTION, INFINITE_DE_SOLUTIONS
                if (!s.isEmpty()) erreurs.add("Aucune solution numérique attendue, obtenu " + s);
        }
        return erreurs;
    }

    static boolean toutesReelles(List<?> s) {
        for (Object x : s) {
            if (!(x instanceof Number) || Double.isNaN(reel(x)) || Double.isInfinite(reel(x))) return false;
        }
        return true;
    }

    static double reel(Object x) {
        return ((Number) x).doubleValue();
    }

    // ========================================================================
    // 5. TESTS (une bulle verte / rouge par cas dans IntelliJ / VS Code)
    // ========================================================================

    @Nested
    @DisplayName("1. Les 9 catégories et leurs valeurs")
    class Categories {

        @Test
        @DisplayName("chaque catégorie a une valeur qui lui appartient")
        void valeursDansLeursCategories(TestReporter rapport) {
            rapport.publishEntry("graine", GRAINE + "   (rejouer avec -Dgraine=" + GRAINE + ")");
            for (int i = 0; i < CATEGORIES.size(); i++) {
                Categorie cat = CATEGORIES.get(i);
                rapport.publishEntry(cat.nom, format(VALEURS[i]) + "   (" + cat.intervalle() + ")");
                assertTrue(cat.contient(VALEURS[i]), cat.nom + " : " + VALEURS[i] + " hors de " + cat.intervalle());
            }
        }

        @Test
        @DisplayName("les exemples du sujet appartiennent bien à leur catégorie")
        void exemplesDuSujet() {
            for (Categorie cat : CATEGORIES) {
                assertTrue(cat.contient(cat.exemple), cat.nom + " : exemple " + cat.exemple);
            }
        }
    }

    @Nested
    @DisplayName("2. Générateur Pairwise (allpairs4j)")
    class GenerateurAllPairs {

        @Test
        @DisplayName("réduit les 729 combinaisons de catégories")
        void reduitLeProduitCartesien(TestReporter rapport) {
            int exhaustif = (int) Math.pow(CATEGORIES.size(), PARAMETRES.length);
            rapport.publishEntry("cas", exhaustif + " combinaisons exhaustives → " + CAS_GENERES.size() + " cas allpairs4j");
            assertTrue(CAS_GENERES.size() < exhaustif,
                CAS_GENERES.size() + " cas générés pour " + exhaustif + " combinaisons");
        }

        @Test
        @DisplayName("couvre 100 % des paires de catégories (a,b), (b,c) et (a,c)")
        void couvreToutesLesPaires() {
            assertEquals(new ArrayList<String>(), pairesManquantes());
        }

        @Test
        @DisplayName("utilise chacune des 9 catégories pour a, b et c")
        void utiliseChaqueCategorie() {
            for (String p : PARAMETRES) {
                Set<Object> utilisees = CAS_GENERES.stream().map(cas -> cas.get(p)).collect(Collectors.toSet());
                assertEquals(new HashSet<Object>(NOMS_CATEGORIES), utilisees, "catégories utilisées pour " + p);
            }
        }
    }

    @Nested
    @DisplayName("3. Chargement dynamique du solveur (réflexion)")
    class ChargementDynamique {

        @Test
        @DisplayName("la classe de calcul est chargée et instanciée par réflexion")
        void classeChargeeEtInstanciee(TestReporter rapport) {
            rapport.publishEntry("classe", NOM_CLASSE);
            if (SOLVEUR.erreur != null) fail("ÉCHOUÉ — " + SOLVEUR.erreur);
            rapport.publishEntry("méthode", SOLVEUR.methode.toString());
            rapport.publishEntry("instance", String.valueOf(SOLVEUR.instance));
        }
    }

    @Nested
    @DisplayName("4. Matrice allpairs4j × oracle")
    class MatriceOracle {

        @ParameterizedTest(name = "Cas {index} : a={0}, b={1}, c={2}  [{3}]")
        @MethodSource({"quadratique.SolveurTest#matriceAllPairs", "quadratique.SolveurTest#casImposes"})
        void solveurConformeALOracle(double a, double b, double c, String categories) throws Exception {
            // Solveur absent : verdict ÉCHOUÉ explicite, les autres cas continuent.
            if (SOLVEUR.erreur != null) fail("ÉCHOUÉ — " + SOLVEUR.erreur);

            Object reponse;
            try {
                reponse = SOLVEUR.methode.invoke(SOLVEUR.instance, a, b, c);
            } catch (InvocationTargetException e) {
                throw new AssertionError("ÉCHOUÉ — le solveur a levé une exception pour ("
                    + a + ", " + b + ", " + c + ") : " + e.getCause(), e.getCause());
            }

            List<String> erreurs = verifierAvecOracle(a, b, c, reponse);
            if (!erreurs.isEmpty()) fail(String.join("\n", erreurs));
        }
    }

    // ========================================================================
    // Utilitaires
    // ========================================================================

    /** Propriété système (-D…), puis variable d'environnement, puis valeur par défaut. */
    static String lireParametre(String propriete, String variableEnv, String defaut) {
        String v = System.getProperty(propriete);
        if (v == null || v.trim().isEmpty()) v = System.getenv(variableEnv);
        return v == null || v.trim().isEmpty() ? defaut : v;
    }

    /** 1.0E-6 → "0.000001", 100000.0 → "100000". */
    static String format(double x) {
        if (Double.isInfinite(x)) return "∞";
        return BigDecimal.valueOf(x).stripTrailingZeros().toPlainString();
    }
}
