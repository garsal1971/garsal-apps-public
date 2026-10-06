-- Il nome dell'app diventa "Ta Firi?" (con punto interrogativo)
UPDATE cm_apps
   SET title       = 'Ta Firi?',
       description = 'Sfide a tempo — Ta Firi?'
 WHERE html_file = 'ta-firi.html' OR title IN ('Ta Firi', 'Sfide');
