#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <chrono>
#include "llama.h"
static llama_model * model=nullptr;
static llama_context * ctx=nullptr;
static std::mutex guard;
static std::atomic<bool> cancelled{false};
static std::chrono::steady_clock::time_point deadline;
static void fail(JNIEnv *e,const char *s){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),s);}
extern "C" JNIEXPORT void JNICALL Java_io_github_ndndndn1_meg_LocalLlm_load(JNIEnv *e,jobject,jstring path){
 std::lock_guard<std::mutex> lock(guard);
 if(ctx){llama_free(ctx);ctx=nullptr;}if(model){llama_model_free(model);model=nullptr;}
 llama_backend_init();auto p=llama_model_default_params();p.n_gpu_layers=0;
 const char *file=e->GetStringUTFChars(path,nullptr);model=llama_model_load_from_file(file,p);e->ReleaseStringUTFChars(path,file);
 if(!model){fail(e,"로컬 모델을 읽지 못했습니다.");return;}
 auto cp=llama_context_default_params();cp.n_ctx=4096;cp.n_batch=512;cp.n_threads=4;cp.n_threads_batch=4;cp.abort_callback=[](void*){return cancelled.load()||std::chrono::steady_clock::now()>deadline;};ctx=llama_init_from_model(model,cp);if(!ctx)fail(e,"모델 실행 메모리가 부족합니다.");
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_ndndndn1_meg_LocalLlm_generate(JNIEnv *e,jobject,jstring input){
 std::lock_guard<std::mutex> lock(guard);if(!ctx){fail(e,"먼저 로컬 모델을 준비하세요.");return nullptr;}
 struct ClearContext { ~ClearContext(){ if(ctx)llama_memory_clear(llama_get_memory(ctx),true); } } clearContext;
 cancelled=false;deadline=std::chrono::steady_clock::now()+std::chrono::seconds(25);
 const char *s=e->GetStringUTFChars(input,nullptr);std::string prompt(s);e->ReleaseStringUTFChars(input,s);
 const auto *vocab=llama_model_get_vocab(model);int n=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
 if(n<=0||n>3100){fail(e,"검증할 내용이 너무 깁니다. 자료 범위를 줄이세요.");return nullptr;}
 std::vector<llama_token> tokens(n);llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),n,true,true);llama_memory_clear(llama_get_memory(ctx),true);
 for(int i=0;i<n;i+=512){auto batch=llama_batch_get_one(tokens.data()+i,std::min(512,n-i));if(llama_decode(ctx,batch)){fail(e,"모델 문맥 처리 실패");return nullptr;}}
 auto *sampler=llama_sampler_init_greedy();std::string result;
 for(int i=0;i<700;i++){auto token=llama_sampler_sample(sampler,ctx,-1);if(llama_vocab_is_eog(vocab,token))break;char piece[512];int length=llama_token_to_piece(vocab,token,piece,sizeof(piece),0,true);if(length>0)result.append(piece,length);auto batch=llama_batch_get_one(&token,1);if(llama_decode(ctx,batch))break;}
 llama_sampler_free(sampler);llama_memory_clear(llama_get_memory(ctx),true);
 if(cancelled.load()||std::chrono::steady_clock::now()>deadline){fail(e,"검증 시간 초과 또는 취소");return nullptr;}
 // JNI NewStringUTF expects modified UTF-8; construct through Java's UTF-8 decoder.
 jbyteArray bytes=e->NewByteArray(result.size());e->SetByteArrayRegion(bytes,0,result.size(),reinterpret_cast<const jbyte*>(result.data()));
 jclass stringClass=e->FindClass("java/lang/String");jmethodID ctor=e->GetMethodID(stringClass,"<init>","([BLjava/lang/String;)V");jstring encoding=e->NewStringUTF("UTF-8");return (jstring)e->NewObject(stringClass,ctor,bytes,encoding);
}
extern "C" JNIEXPORT void JNICALL Java_io_github_ndndndn1_meg_LocalLlm_close(JNIEnv*,jobject){std::lock_guard<std::mutex>lock(guard);if(ctx)llama_free(ctx);if(model)llama_model_free(model);ctx=nullptr;model=nullptr;}
extern "C" JNIEXPORT void JNICALL Java_io_github_ndndndn1_meg_LocalLlm_cancel(JNIEnv*,jobject){cancelled=true;}
