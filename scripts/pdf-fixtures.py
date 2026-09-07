#!/usr/bin/env python3
"""Create deterministic synthetic PDFs; never uses meeting documents. No dependencies."""
import hashlib
import json
import sys
from pathlib import Path

def pdf(pages, lines):
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    kids=[]
    for page in range(pages):
        pid=len(objects)+1; kids.append(f'{pid} 0 R')
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {pid+1} 0 R >>'.encode())
        text=['BT /F1 9 Tf 30 760 Td 12 TL']
        for i in range(lines):
            sentence=f'Synthetic project Orion launch budget is 120 million won. Page {page+1}, row {i+1}.'
            text.append(f'({sentence}) Tj T*')
        text.append('ET'); stream='\n'.join(text).encode()
        objects.append(f'<< /Length {len(stream)} >>\nstream\n'.encode()+stream+b'\nendstream')
    objects[1]=f'<< /Type /Pages /Kids [{" ".join(kids)}] /Count {pages} >>'.encode()
    result=bytearray(b'%PDF-1.4\n');offsets=[0]
    for i,obj in enumerate(objects,1):
        offsets.append(len(result));result+=f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n'
    table=f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    for o in offsets[1:]:table+=f'{o:010d} 00000 n \n'.encode()
    table+=f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n'.encode()
    # Padding is a PDF comment before xref, keeping EOF discoverable by strict readers.
    xref=1_500_000-len(table)-len(b'0000000\n%%EOF\n')
    pad=xref-len(result)
    result+=b'%'+b'p'*(pad-2)+b'\n'+table+f'{xref}\n%%EOF\n'.encode()
    return result

out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
for name,pages,lines in [('light',3,4),('dense',150,45)]:
    b=pdf(pages,lines);(out/f'meg-v2-{name}.pdf').write_bytes(b)
    print(json.dumps(dict(name=name,bytes=len(b),pages=pages,sha256=hashlib.sha256(b).hexdigest())))
