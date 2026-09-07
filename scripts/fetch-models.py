"""Fetch exactly the reviewed manifest, verifying every downloaded byte."""
import argparse,hashlib,json,pathlib,urllib.request,concurrent.futures
p=argparse.ArgumentParser();p.add_argument('directory',type=pathlib.Path);args=p.parse_args();args.directory.mkdir(parents=True,exist_ok=True)
root=pathlib.Path(__file__).resolve().parents[1]
assets=json.loads((root/'contracts/models.json').read_text())['assets']
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def get(a):
 path=args.directory/a['id']
 if path.exists() and digest(path)==a['sha256']:return a['id']+' cached'
 tmp=path.with_name(path.name+'.part')
 with urllib.request.urlopen(a['url'],timeout=120) as r,tmp.open('wb') as f:
  for b in iter(lambda:r.read(1048576),b''):f.write(b)
 if digest(tmp)!=a['sha256']:tmp.unlink();raise ValueError('digest mismatch '+a['id'])
 tmp.replace(path);return a['id']+' verified'
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 for result in pool.map(get,assets):print(result,flush=True)
