"""Exercise production installer with the exact transferred SL.Tube binary."""
from pathlib import Path
import re, subprocess, tempfile, zipfile
root=Path(__file__).resolve().parents[1]
s=(root/'app/src/main/java/com/satlink/geckoviewtest/BundledEngineInstaller.java').read_text()
s=re.sub(r'^package .*;\n','',s,flags=re.M)
header='\n'.join(re.findall(r'^import .*;$',s,re.M))
s=re.sub(r'^import .*;\n','',s,flags=re.M)
test='''
class BundledEngineTest {
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  File bundle=new File(args[0]);Path root=Files.createTempDirectory("satlink-installer-test");File target=root.resolve("yt-dlp").toFile();
  try {
   check(BundledEngineInstaller.install(target,new FileInputStream(bundle),"",()->"2026.08.19"),"Fresh install not performed");
   check(Files.mismatch(bundle.toPath(),target.toPath())==-1,"Fresh installation bytes differ");
   Files.writeString(target.toPath(),"newer engine");
   check(!BundledEngineInstaller.install(target,new FileInputStream(bundle),"2026.09.30",()->{throw new AssertionError("Downgrade attempted");}),"Newer engine overwritten");
   check(Files.readString(target.toPath()).equals("newer engine"),"Newer engine changed");
   Files.writeString(target.toPath(),"old engine");
   try {BundledEngineInstaller.install(target,new FileInputStream(bundle),"2025.01.01",()->"invalid");throw new AssertionError("Bad engine verification accepted");}catch(IOException expected){}
   check(Files.readString(target.toPath()).equals("old engine"),"Failed engine did not roll back");
   try {BundledEngineInstaller.install(target,new ByteArrayInputStream(new byte[]{1,2,3}),"2025.01.01",()->"2026.08.19");throw new AssertionError("Corrupt binary accepted");}catch(IOException expected){}
   check(Files.readString(target.toPath()).equals("old engine"),"Corrupt bundle damaged engine");
   check(BundledEngineInstaller.install(target,new FileInputStream(bundle),"2025.01.01",()->"2026.08.19"),"Upgrade missing");
   check(!BundledEngineInstaller.needsUpgrade("2026.08.19.232658"),"Same-day nightly downgraded");
   check(Files.list(root).count()==1,"Temporary files left behind");
   System.out.println("PASS bundled engine: exact SL.Tube bytes, SHA-256, fresh install, upgrade, no downgrade, validation failure rollback, corrupt-file rejection, staging cleanup");
  }finally{try(var files=Files.list(root)){for(Path p:files.toList())Files.deleteIfExists(p);}Files.delete(root);}
 }
}
'''
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)/'BundledEngineTest.java';p.write_text(header+test+s)
 subprocess.run(['java',str(p),str(root/'app/src/main/res/raw/ytdlp')],check=True)
subprocess.run(['python3',str(root/'app/src/main/res/raw/ytdlp'),'--version'],check=True)
with zipfile.ZipFile(root/'app/src/main/res/raw/ytdlp') as z:
 assert {'yt_dlp_ejs/yt/solver/core.min.js','yt_dlp_ejs/yt/solver/lib.min.js'}<=set(z.namelist())
 print('PASS engine execution --version and bundled EJS solver files (host Python, not Android)')
