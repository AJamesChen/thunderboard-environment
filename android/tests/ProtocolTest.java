import com.jamesc.bg22monitor.OtaImage;
import com.jamesc.bg22monitor.SensorCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Run with Java assertions enabled and a real generated .bg22ota package. */
public final class ProtocolTest {
    private static void rejects(byte[] value) throws Exception {
        try { OtaImage.read(new ByteArrayInputStream(value)); throw new AssertionError("Invalid package accepted"); }
        catch (IOException expected) { }
    }
    private static Map<String,byte[]> entries(byte[] value) throws Exception {
        Map<String,byte[]> result=new LinkedHashMap<>();
        try (ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(value))) {
            ZipEntry entry;
            while ((entry=zip.getNextEntry())!=null) result.put(entry.getName(),zip.readAllBytes());
        }
        return result;
    }
    private static byte[] zip(Map<String,byte[]> entries) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(out)) {
            for (Map.Entry<String,byte[]> entry:entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        assert Math.abs(SensorCodec.temperature(new byte[]{0x76,0x0b})-29.34)<0.0001;
        assert Math.abs(SensorCodec.temperature(new byte[]{0x2d,(byte)0xfb})+12.35)<0.0001;
        assert Math.abs(SensorCodec.humidity(new byte[]{0x48,0x10})-41.68)<0.0001;
        try { SensorCodec.humidity(new byte[]{(byte)0xff,(byte)0xff}); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }

        assert Arrays.equals(SensorCodec.encodePeriodSeconds(5),new byte[]{(byte)0x88,0x13,0,0});
        for (int seconds:new int[]{1,2,5,60,3600})
            assert SensorCodec.decodePeriodSeconds(SensorCodec.encodePeriodSeconds(seconds))==seconds;
        for (int invalid:new int[]{0,-1,3601,Integer.MAX_VALUE}) {
            try { SensorCodec.encodePeriodSeconds(invalid); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { }
        }
        for (byte[] invalid:new byte[][]{new byte[0],new byte[3],new byte[5],new byte[]{1,0,0,0},new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff}}) {
            try { SensorCodec.decodePeriodSeconds(invalid); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { }
        }
        assert SensorCodec.staleAfterMillis(2)==10000;
        assert SensorCodec.staleAfterMillis(60)==122000;
        assert SensorCodec.staleAfterMillis(3600)==7202000;
        try { SensorCodec.temperature(new byte[]{1}); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }

        byte[] valid=Files.readAllBytes(Path.of(args[0]));
        OtaImage image=OtaImage.read(new ByteArrayInputStream(valid));
        assert image.version>=2 && image.bytes.length<=OtaImage.SLOT_SIZE;
        rejects(new byte[]{1,2,3});
        Map<String,byte[]> entries=entries(valid);
        String manifest=new String(entries.get("manifest.properties"),StandardCharsets.UTF_8);
        entries.put("manifest.properties",manifest.replace("BRD4184A-A02","BRD4184B-A02").getBytes(StandardCharsets.UTF_8));
        rejects(zip(entries));
        entries=entries(valid); entries.get("application.gbl")[20]^=1; rejects(zip(entries));
        // Recompute the transport checksum: the internal GBL CRC/product checks must still reject corruption.
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entries.get("application.gbl")));
        entries.put("manifest.properties",manifest.replaceFirst("sha256=.*","sha256="+digest).getBytes(StandardCharsets.UTF_8));
        rejects(zip(entries));
        entries=entries(valid); entries.put("manifest.properties",manifest.replace("version="+image.version,"version="+(image.version+1)).getBytes(StandardCharsets.UTF_8));
        rejects(zip(entries));
        entries=entries(valid); entries.put("application.gbl",new byte[OtaImage.SLOT_SIZE+1]); rejects(zip(entries));
        entries=entries(valid); entries.put("../unexpected",new byte[0]); rejects(zip(entries));
        System.out.println("PASS: sensor decoding, GBL package, board/version matching, checksums, corruption, size, and ZIP entry restrictions");
    }
}
