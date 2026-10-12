#!/usr/bin/env python3
"""Download pinned German first-stage weights, no mutable model URLs."""
import concurrent.futures,urllib.request,sys
from pathlib import Path
root=Path(sys.argv[1]);root.mkdir(parents=True,exist_ok=True)
base='https://huggingface.co/Xenova/opus-mt-de-en/resolve/399dfd68706739fffd503f876093e455ae268a06/onnx/'
def get(name):
 target=root/(name+'.onnx')
 for attempt in range(3):
  try:
   with urllib.request.urlopen(base+name+'_model_quantized.onnx',timeout=45) as response,target.open('wb') as out:
    size=0
    while True:
     data=response.read(1024*1024)
     if not data:break
     size+=len(data)
     if size>65_000_000:raise ValueError('Model too large')
     out.write(data)
   return
  except Exception:
   target.unlink(missing_ok=True)
   if attempt==2:raise
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(get,['encoder','decoder']))
