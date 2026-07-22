package id.behavio.iso.scenario;

/**
 * Gangguan yang disuntikkan pada level TRANSPORT.
 *
 * <p>Lebih kaya daripada padanan HTTP-nya: TCP memungkinkan kegagalan yang tak punya
 * bentuk di HTTP — <b>koneksi diputus</b> dan <b>diam total</b>. Keduanya justru kegagalan
 * paling sering ditemui saat integrasi host, dan paling jarang diuji klien.
 *
 * @param delayMillis tunda sebelum membalas (menguji timeout klien)
 * @param noResponse  tidak membalas tapi koneksi TETAP hidup — klien menggantung sampai
 *                    timeout-nya sendiri; kegagalan paling menyesatkan di lapangan
 * @param drop        tidak membalas DAN koneksi ditutup — klien mendapat EOF
 * @param corrupt     balasan dikirim dengan byte dirusak (menguji penanganan parse gagal)
 */
public record IsoFault(long delayMillis, boolean noResponse, boolean drop, boolean corrupt) {

    private static final IsoFault NONE = new IsoFault(0, false, false, false);

    public static IsoFault none() {
        return NONE;
    }

    public boolean isNone() {
        return delayMillis <= 0 && !noResponse && !drop && !corrupt;
    }

    public static IsoFault delay(long millis) {
        return new IsoFault(millis, false, false, false);
    }

    public static IsoFault dropConnection() {
        return new IsoFault(0, false, true, false);
    }

    public static IsoFault silence() {
        return new IsoFault(0, true, false, false);
    }

    public static IsoFault corruptResponse() {
        return new IsoFault(0, false, false, true);
    }
}
