import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Build-time patch for Apple's MusicKit SDK (mediaplayback 1.1.1, the latest release).
 *
 * The SDK parses the `songId` of its webPlayback response with `JsonReader.nextInt()`, which throws
 * for catalog ids above Int.MAX_VALUE (most releases since 2026). The SDK swallows the exception
 * and those songs buffer forever. In the one method that parses it, this swaps the int read for
 * `MusicKitSongIds.read` (reads a long) and the later int-to-long widening for
 * `MusicKitSongIds.widen` (returns that long). The AAR itself is untouched.
 *
 * Fails the build if the method no longer looks as expected, rather than shipping a silent no-op.
 */
abstract class MusicKitSongIdFix : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData) = classData.className == TARGET_CLASS

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return if (name == "a" && descriptor == TARGET_METHOD) SongIdVisitor(next) else next
            }
        }

    private class SongIdVisitor(next: MethodVisitor) : MethodVisitor(Opcodes.ASM9, next) {
        private var reads = 0
        private var widens = 0

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            if (opcode == Opcodes.INVOKESTATIC && owner == TARGET_OWNER && name == "a" && descriptor == "(Landroid/util/JsonReader;I)I") {
                reads++
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "read", descriptor, false)
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
        }

        override fun visitInsn(opcode: Int) {
            if (opcode == Opcodes.I2L) {
                widens++
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "widen", "(I)J", false)
            } else {
                super.visitInsn(opcode)
            }
        }

        override fun visitEnd() {
            check(reads == 1 && widens == 1) {
                "MusicKit SDK changed: expected 1 songId read and 1 widening in $TARGET_CLASS, found $reads and $widens"
            }
            super.visitEnd()
        }
    }

    private companion object {
        const val TARGET_CLASS = "com.apple.android.music.playback.g.h"
        const val TARGET_OWNER = "com/apple/android/music/playback/g/h"
        const val TARGET_METHOD = "(Landroid/util/JsonReader;)Ljava/util/List;"
        const val HELPER = "com/aaosmusic/providers/apple/MusicKitSongIds"
    }
}
