import glob,re,sys
mode,names=sys.argv[1],sys.argv[2:]
cases=[]
for f in glob.glob("build/test-results/fastTest/TEST-*.xml"):
    for m in re.finditer(r'<testcase name="([^"]*)"[^>]*?(?:/>|>(.*?)</testcase>)',open(f,encoding="utf-8").read(),re.S):
        cases.append((m[1],bool(m[2]) and ("<failure" in m[2] or "<error" in m[2])))
bad=[]
for n in names:
    hits=[c for c in cases if n in c[0]]
    if not hits: bad.append(n+":not-run")
    elif mode=="red" and not all(x[1] for x in hits): bad.append(n+":passed")
    elif mode=="green" and any(x[1] for x in hits): bad.append(n+":failed")
print(mode,len(names),"rules; problems:",bad); sys.exit(1 if bad else 0)
