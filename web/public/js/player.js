// Puts the camera's stream on the <video>. ontrack fires once per track (video and audio share one stream);
// re-assigning srcObject would abort the pending play() and look like Safari blocking sound.

export function showStream(video, stream, { onSoundBlocked }) {
  if (video.srcObject === stream) return;
  video.srcObject = stream;
  video.play().catch((err) => {
    if (err?.name === 'NotAllowedError') onSoundBlocked();
  });
}
