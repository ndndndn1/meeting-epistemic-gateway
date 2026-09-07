class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.pending = [];
    this.n = 0;
  }
  process(inputs) {
    const ch = inputs[0]?.[0];
    if (ch) {
      const copy = new Float32Array(ch);
      this.pending.push(copy);
      this.n += copy.length;
      if (this.n >= 2048) {
        const a = new Float32Array(this.n);
        let p = 0;
        for (const b of this.pending) {
          a.set(b, p);
          p += b.length;
        }
        this.port.postMessage(a, [a.buffer]);
        this.pending = [];
        this.n = 0;
      }
    }
    return true;
  }
}
registerProcessor("pcm-capture", PcmCapture);
