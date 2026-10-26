# Chinese → Russian

Player 0.7.1, AFP Translate 0.3.0, package opus-zh-ru-v1. Both Chinese → English and English → Russian models are included. Install through Extensions → Download extensions; choose installed languages in Extensions. Manual ZIP import selects its recognised model.

General meaning is the target; intermediate English can lose nuance, names, gender or imagery. Rhyme and meter are not guaranteed. No network is used for inference. English v1/v2 packages and saved translations remain separate.

Six model/tokenizer files are pinned in ModelPackages.kt. Archive paths, duplicates, limits and incomplete packages are checked; failed installs preserve existing packages. Source SentencePiece scores are remapped into the shared Chinese vocabulary. Model provenance, licenses and modifications appear in NOTICE.txt and LICENSE.txt.

Real Java/ONNX inference checks six example sentences, blank paragraphs, repeated chorus, long Unicode text and cancellation. Android unit tests install the real package and test incomplete packages. Physical second-phone installation and timing have not been tested here.

German, French and Spanish are planned, not included.
