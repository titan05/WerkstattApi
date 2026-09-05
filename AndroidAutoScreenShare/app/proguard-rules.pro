# Die Car App Library serialisiert ihre Model-Objekte reflektiv ueber den Bundler
# (Feldnamen muessen erhalten bleiben), und der Host bindet den CarAppService ueber
# den im Manifest genannten Klassennamen.
-keep class androidx.car.app.** { *; }

# Eigener Code bleibt unverschleiert: kostet praktisch nichts (die Groesse kommt aus
# material-icons-extended, das R8 weiterhin ausduennt) und haelt Logcat-Stacktraces
# lesbar.
-keep class at.werkstatt.screenmirror.** { *; }
