// A program with one dominant allocation site, named a priori.
// plantedAllocator allocates byte[] (an array type, which is the case the flat-table
// parser's character class cannot spell) and nothing else in the program allocates
// anything close to it.
public class Planted {
    static volatile Object sink;

    static byte[] plantedAllocator() { return new byte[4096]; }

    static String minorAllocator() { return new String(new char[4]); }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 40_000_000; i++) {
            sink = plantedAllocator();
            if ((i & 0x3FF) == 0) sink = minorAllocator();
        }
        System.out.println("done " + sink);
    }
}
