// React Native 配置 —— 告知 @react-native-community/cli 与 RNGP 我们的
// Android 项目位置。默认从 android/ 自动探测，但本项目是 brownfield，
// Android 模块在 diandi/app 而非标准 android/app 下。
module.exports = {
  project: {
    android: {
      sourceDir: './app',
      appName: 'app',
      manifestPath: './src/main/AndroidManifest.xml',
      packageName: 'com.mianbizhe.diandiji',
    },
  },
};
