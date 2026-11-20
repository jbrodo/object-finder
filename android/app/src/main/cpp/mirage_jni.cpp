// Ponte JNI minimo verso il motore Cactus per Mirage (descrizione immagini, testo letto).
// Tutte le chiamate sono serializzate dal lato Kotlin (MirageEngine).
#include <jni.h>
#include <clocale>
#include <cstdio>
#include <string>
#include <vector>

#ifdef HAVE_CACTUS
#include "cactus.h"
#endif

namespace {
std::string utf(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_it_objectfinder_app_recognition_MirageNative_available(JNIEnv*, jclass) {
#ifdef HAVE_CACTUS
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// Carica i pesi (.cact) dal file: il motore ne fa una copia, il buffer si libera subito.
// Restituisce null se riuscito, altrimenti il messaggio d'errore.
extern "C" JNIEXPORT jstring JNICALL
Java_it_objectfinder_app_recognition_MirageNative_load(JNIEnv* env, jclass, jstring path) {
#ifdef HAVE_CACTUS
    std::string file = utf(env, path);
    FILE* f = std::fopen(file.c_str(), "rb");
    if (!f) return env->NewStringUTF("modello non trovato");
    std::fseek(f, 0, SEEK_END);
    long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    std::vector<unsigned char> data(size > 0 ? static_cast<size_t>(size) : 0);
    size_t read = size > 0 ? std::fread(data.data(), 1, data.size(), f) : 0;
    std::fclose(f);
    if (read != data.size() || data.empty()) return env->NewStringUTF("lettura del modello non riuscita");
    if (cactus_load(data.data(), static_cast<unsigned long long>(data.size())) < 0) {
        const char* error = cactus_last_error();
        return env->NewStringUTF(error ? error : "caricamento non riuscito");
    }
    if ((cactus_models() & CACTUS_VISION) == 0) return env->NewStringUTF("il file non è un modello Mirage");
    return nullptr;
#else
    (void)path;
    return env->NewStringUTF("Mirage non disponibile su questa architettura");
#endif
}

// Descrive un'immagine RGB (width*height*3 byte). Restituisce il JSON di Mirage come byte UTF-8
// (NewStringUTF non accetta i caratteri a 4 byte che il modello può produrre);
// in caso di errore lancia IllegalStateException con il messaggio del motore.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_it_objectfinder_app_recognition_MirageNative_describe(
        JNIEnv* env, jclass, jbyteArray rgb, jint width, jint height, jstring options, jstring language) {
#ifdef HAVE_CACTUS
    jsize length = env->GetArrayLength(rgb);
    if (width <= 0 || height <= 0 || length != width * height * 3) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "dimensioni RGB non valide");
        return nullptr;
    }
    std::vector<unsigned char> pixels(static_cast<size_t>(length));
    env->GetByteArrayRegion(rgb, 0, length, reinterpret_cast<jbyte*>(pixels.data()));
    std::string opts = utf(env, options);
    std::string lang = utf(env, language);
    std::vector<char> out(4 * 1024 * 1024);
    // Il motore scrive i numeri secondo LC_NUMERIC: con una lingua che usa la virgola il JSON
    // diventerebbe invalido ("score":0,359). Punto decimale forzato per la durata della chiamata.
    const char* previous = std::setlocale(LC_NUMERIC, nullptr);
    std::string saved = previous ? previous : "C";
    std::setlocale(LC_NUMERIC, "C");
    int code = cactus_describe(pixels.data(), width, height, opts.c_str(),
                               lang.empty() ? nullptr : lang.c_str(), nullptr,
                               out.data(), static_cast<int>(out.size()));
    std::setlocale(LC_NUMERIC, saved.c_str());
    if (code < 0) {
        const char* error = cactus_last_error();
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error ? error : "analisi non riuscita");
        return nullptr;
    }
    size_t used = std::char_traits<char>::length(out.data());
    jbyteArray result = env->NewByteArray(static_cast<jsize>(used));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(used), reinterpret_cast<const jbyte*>(out.data()));
    return result;
#else
    (void)rgb; (void)width; (void)height; (void)options; (void)language;
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Mirage non disponibile");
    return nullptr;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_it_objectfinder_app_recognition_MirageNative_setThreads(JNIEnv*, jclass, jint threads) {
#ifdef HAVE_CACTUS
    cactus_set_threads(threads);
#else
    (void)threads;
#endif
}
