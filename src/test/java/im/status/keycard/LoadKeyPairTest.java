package im.status.keycard;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LOAD KEY with a plain keypair (no chain code) must never read past the command data. Under SC V2 the byte after
 * the private key is this command's own ciphertext tail, so an applet that decided "extended" from it would fail
 * about 1 load in 256 with 6A80. Such an applet fails this test with probability about 1 - (255/256)^1500 = 99.7%.
 */
public class LoadKeyPairTest {
  @Test
  @DisplayName("1500 random plain keypairs all load")
  void randomKeypairsLoad() throws Exception {
    assumeTrue(NoteStoreTest.SIMULATOR);
    NoteStoreTest.Rig rig = NoteStoreTest.Rig.simulator(null, true);
    rig.unlock();
    KeyPairGenerator g = KeyPairGenerator.getInstance("ECDH", "BC");
    g.initialize(ECNamedCurveTable.getParameterSpec("secp256k1"));
    for (int i = 0; i < 1500; i++) {
      KeyPair kp = g.generateKeyPair();
      assertEquals(0x9000, rig.cmdSet.loadKey(kp).getSw(), "load " + i);
    }
  }
}
