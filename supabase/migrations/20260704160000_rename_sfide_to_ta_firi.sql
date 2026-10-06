-- Rinomina l'app "Sfide" in "Ta Firi" nel launcher AppSphere (il file HTML è stato rinominato
-- da sfide.html a ta-firi.html)
UPDATE cm_apps
   SET title       = 'Ta Firi',
       description = 'Sfide a tempo — Ta Firi',
       html_file   = 'ta-firi.html'
 WHERE html_file = 'sfide.html' OR title = 'Sfide';
