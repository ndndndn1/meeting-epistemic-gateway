import json,urllib.request,hashlib,base64,pathlib
root=pathlib.Path(__file__).resolve().parents[1]
records=json.loads((root/'contracts/mlc-candidates.json').read_text())
def get(url):return urllib.request.urlopen(url,timeout=60).read()
def sri(data):return 'sha256-'+base64.b64encode(hashlib.sha256(data).digest()).decode()
commit=json.loads(get('https://api.github.com/repos/mlc-ai/binary-mlc-llm-libs/commits/main'))['sha']
for r in records:
 repo=r['model'].split('huggingface.co/')[1];d=json.loads(get('https://huggingface.co/api/models/'+repo+'?blobs=true'));r['model']+='/'+'resolve/'+d['sha']+'/'
 r['model_lib']=r['model_lib'].replace('/main/','/'+commit+'/')
 integrity={'config':sri(get(r['model']+'mlc-chat-config.json')),'model_lib':sri(get(r['model_lib'])),'tokenizer':{},'onFailure':'error'}
 for f in d['siblings']:
  if f['rfilename'] in ['tokenizer.json','tokenizer_config.json']:integrity['tokenizer'][f['rfilename']]=sri(get(r['model']+f['rfilename']))
 r['integrity']=integrity
 r['files']=[{'name':f['rfilename'],'bytes':f.get('size'),'sha256':f.get('lfs',{}).get('sha256')} for f in d['siblings']]
 r['license']='apache-2.0'
(root/'contracts/mlc-models.json').write_text(json.dumps(records,indent=2)+'\n')
print('web models pinned')
