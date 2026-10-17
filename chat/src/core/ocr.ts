// 截图文字识别：iOS 原生壳走 Vision（RecentScreenshot 插件），浏览器走 tesseract.js（懒加载）。
// 两条路都在本机完成，图片不出设备。
import { Capacitor, registerPlugin } from '@capacitor/core';

export interface ScreenshotInfo {
  found: boolean;
  id?: string;
  createdAt?: number;
  /** 小图 data URL，给对话里预览用 */
  thumbnail?: string;
}

interface RecentScreenshotPlugin {
  latest(opts: { withinSeconds: number }): Promise<ScreenshotInfo>;
  recognize(opts: { id: string }): Promise<{ lines: string[] }>;
}

const RecentScreenshot = registerPlugin<RecentScreenshotPlugin>('RecentScreenshot');

export const isNativeIOS = () => Capacitor.getPlatform() === 'ios';

export async function latestScreenshot(withinSeconds = 60): Promise<ScreenshotInfo> {
  if (!isNativeIOS()) return { found: false };
  return RecentScreenshot.latest({ withinSeconds });
}

export async function recognizeNative(id: string): Promise<string[]> {
  return (await RecentScreenshot.recognize({ id })).lines;
}

export async function recognizeFile(file: Blob, onProgress?: (p: number) => void): Promise<string[]> {
  const { createWorker } = await import('tesseract.js');
  const worker = await createWorker(['chi_sim', 'eng'], 1, {
    logger: (m: { status: string; progress: number }) => {
      if (m.status === 'recognizing text') onProgress?.(m.progress);
    },
  });
  try {
    const { data } = await worker.recognize(file);
    return data.text.split('\n').map((l: string) => l.trim()).filter(Boolean);
  } finally {
    await worker.terminate();
  }
}

/** 把用户选的图片缩成小图 data URL（存进对话记录，localStorage 放得下） */
export async function thumbnailOf(file: Blob, max = 240): Promise<string> {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, max / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bmp.width * scale);
  canvas.height = Math.round(bmp.height * scale);
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.7);
}
