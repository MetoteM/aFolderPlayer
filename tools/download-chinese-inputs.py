#!/usr/bin/env python3
import concurrent.futures,urllib.request,sys
from pathlib import Path
root=Path(sys.argv[1]);root.mkdir(parents=True,exist_ok=True)
base='https://huggingface.co/Xenova/opus-mt-zh-en/resolve/39d480d52a9ea3065a1f117adfe4dbc55de10e6f/onnx/'
jobs=[(base+'encoder_model_quantized.onnx',root/'encoder.onnx'),(base+'decoder_model_quantized.onnx',root/'decoder.onnx'),('https://github.com/MetoteM/aFolderPlayer/releases/download/v0.7.0/AFP_English_Russian_v2.zip',root/'english.zip')]
def get(job):
 u,p=job
 for attempt in range(3):
  try:
   with urllib.request.urlopen(u,timeout=60) as response,p.open('wb') as out:
    size=0
    while True:
     b=response.read(1024*1024)
     if not b:break
     size+=len(b)
     if size>150_000_000:raise ValueError('Input too large')
     out.write(b)
   return
  except Exception:
   p.unlink(missing_ok=True)
   if attempt==2:raise
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(get,jobs))
