# Règles R8 pour Toque (Connect Android 6.0 & Smart Android 8.1 vmSafeMode).
# On réduit et optimise le bytecode (suppression du code mort de kotlin-stdlib et inlining),
# mais sans obfusquer (`-dontobfuscate`) afin que les traces d'erreur dans `journal.txt`
# restent directement lisibles sans fichier de mapping.

-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Énumérations sérialisées par .name / .valueOf() entre processus (:machine), JSON et SQLite.
-keep enum app.toque.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
