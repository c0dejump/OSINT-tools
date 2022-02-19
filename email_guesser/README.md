# email_guesser
Guessing BF email based on emailGuesser by WhiteHatInspector (https://github.com/WhiteHatInspector/emailGuesser)

## Usage:
```
usage: email_guesser.py [-h] [-i IDENTITY] [-p PSEUDO] [-b BIRTH_YEAR] [-k KEYWORD]

optional arguments:
   -h, --help     show this help message and exit
   > General:
     -i IDENTITY    Identity, exemple: -i john_doe
     -p PSEUDO      Pseudo, exemple: -p codejump
   > Assistance:
     -b BIRTH_YEAR  birth year, exemple: -b 1999 (yeah my birth year)
     -k KEYWORD     Keyword, the script will be based on this, exemple: -k security; -k pro
```

## Feature:

- [x] Multithreading
- [x] Skypli + Epieos check
- [x] Format supported: "gmail.com", "hotmail.com", "orange.fr", "yopmail.com", "protonmail.com"
