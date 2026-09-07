export class Voice {
  private fallback?: Worker;
  private fallbackReady = false;
  private context?: AudioContext;
  private source?: AudioBufferSourceNode;
  private deadline?: ReturnType<typeof setTimeout>;
  speaking = false;
  private interrupted = false;
  private generation = 0;
  private remaining = "";
  available() {
    return (
      this.fallbackReady ||
      speechSynthesis
        .getVoices()
        .some((v) => v.lang.startsWith("ko") && v.localService)
    );
  }
  async prepareFallback(progress: (s: string) => void) {
    this.context ??= new AudioContext();
    await this.context.resume();
    this.fallback?.terminate();
    this.fallback = new Worker(
      new URL(
        "tts-worker.js",
        new URL(import.meta.env.BASE_URL, location.origin),
      ),
    );
    await new Promise<void>((resolve, reject) => {
      this.fallback!.onmessage = (e) => {
        const d = e.data;
        if (d.type === "progress") progress(d.text);
        if (d.type === "ready") {
          this.fallbackReady = true;
          resolve();
        }
        if (d.type === "error") {
          this.speaking = false;
          progress(d.message);
          reject(Error(d.message));
        }
        if (d.type === "audio" && d.generation === this.generation) {
          const buffer = this.context!.createBuffer(
            1,
            d.samples.length,
            d.rate,
          );
          buffer.copyToChannel(d.samples, 0);
          this.source = this.context!.createBufferSource();
          this.source.buffer = buffer;
          this.source.connect(this.context!.destination);
          this.source.onended = () => {
            if (d.generation === this.generation) this.speaking = false;
          };
          this.source.start();
        }
      };
      this.fallback!.onerror = () =>
        reject(Error("로컬 음성 엔진 초기화 실패"));
      this.fallback!.postMessage({
        type: "init",
        base: new URL(import.meta.env.BASE_URL, location.origin).href,
      });
    });
  }
  say(text: string) {
    this.stop();
    this.speaking = true;
    this.interrupted = false;
    this.remaining = text;
    this.speak(text, this.generation);
    this.deadline = setTimeout(() => this.stop(), 11000);
  }
  private speak(text: string, generation: number) {
    const v = speechSynthesis
      .getVoices()
      .find((v) => v.lang.startsWith("ko") && v.localService);
    if (!v) {
      if (this.fallbackReady) {
        this.fallback!.postMessage({ type: "say", text, generation });
      } else this.speaking = false;
      return;
    }
    const u = new SpeechSynthesisUtterance(text);
    u.lang = "ko-KR";
    u.voice = v;
    u.rate = 1.15;
    u.onboundary = (e) => {
      this.remaining = text.slice(e.charIndex);
    };
    u.onend = () => {
      if (this.generation === generation) this.speaking = false;
    };
    u.onerror = () => {
      if (this.generation === generation) this.speaking = false;
    };
    speechSynthesis.speak(u);
  }
  interrupt() {
    if (!this.speaking || this.interrupted) return;
    this.interrupted = true;
    this.generation++;
    speechSynthesis.cancel();
    this.source?.stop();
    this.source = undefined;
    this.speak(
      "제 발언을 들어주시고 끊지 말아주세요. " + this.remaining,
      this.generation,
    );
  }
  stop() {
    clearTimeout(this.deadline);
    this.generation++;
    speechSynthesis.cancel();
    this.source?.stop();
    this.source = undefined;
    this.speaking = false;
  }
}
