"""Exercise the actual Java format helpers without Android SDK dependencies."""
from pathlib import Path
import tempfile
import subprocess
root = Path(__file__).resolve().parents[1]
s = (root/'app/src/main/java/com/satlink/geckoviewtest/MainActivity.java').read_text()
metadata = s[s.index('    private static void applyCapturedFormatMetadata'):s.index('    private View createFormatCard')]
support = s[s.index('    private static boolean isSupportedModernVideo'):s.index('    private GradientDrawable roundedBackground')]
source = '''import java.util.Locale;
class FormatRulesTest {
 static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
 static class MediaFormatItem { String ext, videoCodec; int height; double fps; boolean hasAudio; }
''' + metadata + support + '''
 static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
 public static void main(String[] args) {
  MediaFormatItem item = new MediaFormatItem(); item.videoCodec="H.264";
  for (String ext : new String[]{"mkv", "ts", "mpegts", "mov", "m4v"}) {
   item.ext=ext; check(isSupportedModernVideo(item), "Missing video container " + ext);
  }
  item.ext="mp4"; item.videoCodec="AV1";
  check(isSupportedModernVideo(item), "AV1 MP4 was filtered out");
  item.ext="mp3"; check(!isSupportedModernVideo(item), "Audio in video list");
  item.height=0; item.ext="mp4";
  applyCapturedFormatMetadata(item, "", "video/mp4", "1080p60");
  check(item.height==1080, "Resolution must not become 108060");
  item.ext="mkv"; applyCapturedFormatMetadata(item, "", "video/x-matroska", "");
  check(item.ext.equals("mkv"), "MKV container must survive metadata enrichment");
  System.out.println("PASS Java format rules: real MKV/TS, AV1 MP4, audio separation, 1080p60 label");
 }
}
'''
with tempfile.TemporaryDirectory() as tmp:
    target=Path(tmp)/'FormatRulesTest.java'; target.write_text(source)
    subprocess.run(['java', str(target)], check=True)
