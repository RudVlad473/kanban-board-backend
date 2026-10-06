import re,subprocess,sys
P=".planning/quick/261006-kpj-split-docs-code-style-md-enforce-rules-w/261006-kpj-PLAN.md"
sel=None if sys.argv[1]=="all" else set(sys.argv[1].split(","))
phase=sys.argv[2]
base=subprocess.run(["git","show","2fb5710:docs/CODE_STYLE.md"],capture_output=True,text=True,check=True).stdout
rows=[l for l in open(P,encoding="utf-8") if re.match(r"^\| K\d\d ",l)]
hit,skip,none_,miss=0,0,0,[]
for l in rows:
    c=[x.strip() for x in l.strip().strip("|").split("|")]
    rid,fact,kind=c[0],c[1],c[3]
    m=re.search(r"\((\d+)",fact); rule=m[1] if m else None
    if sel is not None and rule not in sel: skip+=1; continue
    if kind=="—": none_+=1; continue
    probe,path=re.search(r"\| `([^`]+)` in `([^`]+)` \|\s*$",l).groups()
    if phase=="pre" and path=="docs/CODE_STYLE.md": skip+=1; continue
    if kind=="V" and probe not in base: miss.append(rid+":not-in-2fb5710"); continue
    try: ok=probe in open(path,encoding="utf-8").read()
    except FileNotFoundError: ok=False
    hit+=ok
    if not ok: miss.append(rid)
print(len(rows),"rows;",hit,"hit;",none_,"no destination;",skip,"deferred or unselected; misses:",miss)
sys.exit(1 if miss or len(rows)!=97 else 0)
