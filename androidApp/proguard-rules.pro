# R8 rules for release builds. The app has almost no reflection surface. Add a
# rule only with a comment saying which crash it fixes.

# Keep line numbers for readable crash reports, but hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
