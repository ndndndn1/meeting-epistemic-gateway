"""Resolve upstream revisions and checksums. Run deliberately when updating models."""
import hashlib,json,pathlib,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
specs=[('asr','csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17','model.int8.onnx','apache-2.0'),('tokens','csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17','tokens.txt','apache-2.0'),('segmentation','csukuangfj/sherpa-onnx-pyannote-segmentation-3-0','model.int8.onnx','mit'),('embedding','csukuangfj/speaker-embedding-models','3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx','apache-2.0'),('llm-1.7b','unsloth/Qwen3-1.7B-GGUF','Qwen3-1.7B-Q4_K_M.gguf','apache-2.0'),('llm-4b','Qwen/Qwen3-4B-GGUF','Qwen3-4B-Q4_K_M.gguf','apache-2.0')]
specs += [(a['id'], '/'.join(a['url'].split('/')[3:5]), a['url'].split('/resolve/')[1].split('/',1)[1], a['license']) for a in json.loads((ROOT/'contracts/models.json').read_text())['assets'] if a['id'].startswith('tts-')]
assets=[]
for id,repo,name,license in specs:
 with urllib.request.urlopen('https://huggingface.co/api/models/'+repo+'?blobs=true') as r:d=json.load(r)
 f=next(x for x in d['siblings'] if x['rfilename']==name)
 url=f'https://huggingface.co/{repo}/resolve/{d["sha"]}/{name}'
 sha=f.get('lfs',{}).get('sha256')
 if not sha:sha=hashlib.sha256(urllib.request.urlopen(url).read()).hexdigest()
 assets.append(dict(id=id,url=url,sha256=sha,bytes=f['size'],revision=d['sha'],license=license))
u='https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx'
b=urllib.request.urlopen(u).read();(ROOT/'web/public/runtime').mkdir(exist_ok=True)
(ROOT/'web/public/runtime/vad.onnx').write_bytes(b)
assets.append(dict(id='vad',url=u,sha256=hashlib.sha256(b).hexdigest(),bytes=len(b),license='mit',revision='sha256'))
d=dict(schemaVersion=1,sherpa='1.13.7',llama='v0.4.0',evaluation='not_evaluated',assets=assets)
for p in [ROOT/'contracts/models.json',ROOT/'web/public/models.json']:p.write_text(json.dumps(d,indent=2)+'\n')
print([(a['id'],a['bytes']) for a in assets])
