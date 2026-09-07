export class Capture {
  private stream?: MediaStream;
  private ctx?: AudioContext;
  private worker?: Worker;
  private node?: AudioWorkletNode;
  private outstanding = 0;
  constructor(
    private onText: (
      text: string,
      speaker: string,
      endedAt: number,
      certain: boolean,
    ) => void,
    private onSpeaker: (id: string, embedding?: number[]) => void,
    private isSpeaking: () => boolean,
    private onInterruption: () => void,
    private onError: (message: string) => void,
    private onProgress: (message: string) => void,
  ) {}
  async start() {
    this.worker = new Worker(
      new URL(
        "speech-worker.js",
        new URL(import.meta.env.BASE_URL, location.origin),
      ),
    );
    try {
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(
          () =>
            reject(Error("음성 모델 준비 시간 초과. 설정·연결을 확인하세요.")),
          180000,
        );
        this.worker!.onerror = () => {
          clearTimeout(timeout);
          reject(Error("음성 런타임 로딩 실패"));
        };
        this.worker!.onmessage = (e) => {
          if (e.data.type === "progress") this.onProgress(e.data.message);
          if (e.data.type === "ready") {
            clearTimeout(timeout);
            resolve();
          }
          if (e.data.type === "error") {
            clearTimeout(timeout);
            reject(Error(e.data.message));
          }
        };
        this.worker!.postMessage({
          type: "init",
          base: new URL(import.meta.env.BASE_URL, location.origin).href,
        });
      });
      this.worker.onerror = () => {
        this.stop();
        this.onError("음성 처리 오류로 마이크를 중지했습니다.");
      };
      this.worker.onmessage = (e) => {
        if (e.data.type === "error") {
          this.stop();
          this.onError(e.data.message);
          return;
        }
        if (e.data.type === "done")
          this.outstanding = Math.max(0, this.outstanding - 1);
        if (e.data.type === "utterance") {
          const { text, speaker, certain, endedAt } = e.data;
          if (speaker) this.onSpeaker(speaker);
          this.onText(text, speaker, endedAt, certain);
        }
      };
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          sampleRate: 16000,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
      this.ctx = new AudioContext({ sampleRate: 16000 });
      await this.ctx.audioWorklet.addModule(
        new URL(
          "audio-worklet.js",
          new URL(import.meta.env.BASE_URL, location.origin),
        ).href,
      );
      this.node = new AudioWorkletNode(this.ctx, "pcm-capture");
      this.node.port.onmessage = (e) => {
        const pcm = e.data as Float32Array;
        if (this.outstanding > 100) {
          this.stop();
          this.onError(
            "음성 처리가 실시간 속도를 따라가지 못해 마이크를 중지했습니다.",
          );
          return;
        }
        const speaking = this.isSpeaking();
        if (speaking) {
          const rms = Math.sqrt(
            pcm.reduce((s, v) => s + v * v, 0) / pcm.length,
          );
          if (rms > 0.12) this.onInterruption();
        }
        this.outstanding++;
        this.worker?.postMessage(
          {
            type: "pcm",
            pcm,
            speaking,
            rate: this.ctx!.sampleRate,
            at: Date.now(),
          },
          [pcm.buffer],
        );
      };
      const src = this.ctx.createMediaStreamSource(this.stream);
      const silent = this.ctx.createGain();
      silent.gain.value = 0;
      src.connect(this.node);
      this.node.connect(silent);
      silent.connect(this.ctx.destination);
    } catch (e) {
      this.stop();
      throw e;
    }
  }
  stop() {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.node?.disconnect();
    void this.ctx?.close();
    this.worker?.terminate();
    this.worker = undefined;
  }
}
