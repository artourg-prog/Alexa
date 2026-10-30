#include <jni.h>
#include <whisper.h>
#include <string>
#include <cmath>

// Model lifetime and decoding are serialized by the Kotlin fallback executor.
extern "C" JNIEXPORT jlong JNICALL
Java_com_tailorshop_counter_conversation_WhisperDecoder_nativeOpen(JNIEnv* env,jclass,jstring path) {
    const char* p=env->GetStringUTFChars(path,nullptr);
    auto params=whisper_context_default_params();params.use_gpu=false;
    auto* ctx=whisper_init_from_file_with_params(p,params);
    env->ReleaseStringUTFChars(path,p);
    return reinterpret_cast<jlong>(ctx);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_tailorshop_counter_conversation_WhisperDecoder_nativeDecode(JNIEnv* env,jclass,jlong handle,jfloatArray pcm,jstring language) {
    auto* ctx=reinterpret_cast<whisper_context*>(handle);
    const auto count=env->GetArrayLength(pcm);
    if(!ctx || count<16000 || count>16000*8)return env->NewStringUTF("");
    float* samples=env->GetFloatArrayElements(pcm,nullptr);
    const char* lang=env->GetStringUTFChars(language,nullptr);
    auto p=whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.n_threads=3;p.translate=false;p.no_context=true;p.no_timestamps=true;
    p.single_segment=true;p.print_special=false;p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;
    p.suppress_blank=true;p.suppress_nst=true;p.language=lang;
    p.temperature=0.f;p.temperature_inc=0.f;p.beam_search.beam_size=5;
    // A bounded rolling window is a wake candidate, never a business action.
    p.max_tokens=96;
    const auto status=whisper_full(ctx,p,samples,count);
    env->ReleaseFloatArrayElements(pcm,samples,JNI_ABORT);
    env->ReleaseStringUTFChars(language,lang);
    std::string result;
    if(status==0) {
        for(int i=0;i<whisper_full_n_segments(ctx);i++) {
            const char* text=whisper_full_get_segment_text(ctx,i);
            if(text) { result+=text;result+=' '; }
        }
    }
    auto bytes=env->NewByteArray(static_cast<jsize>(result.size()));
    env->SetByteArrayRegion(bytes,0,static_cast<jsize>(result.size()),reinterpret_cast<const jbyte*>(result.data()));
    auto stringClass=env->FindClass("java/lang/String");
    auto constructor=env->GetMethodID(stringClass,"<init>","([BLjava/lang/String;)V");
    auto charset=env->NewStringUTF("UTF-8");
    return static_cast<jstring>(env->NewObject(stringClass,constructor,bytes,charset));
}
extern "C" JNIEXPORT void JNICALL
Java_com_tailorshop_counter_conversation_WhisperDecoder_nativeClose(JNIEnv*,jclass,jlong handle) {
    if(handle)whisper_free(reinterpret_cast<whisper_context*>(handle));
}
