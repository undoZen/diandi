const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const path = require('path');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(__dirname, '..');

// npm workspaces 把依赖提升到仓库根 node_modules；metro 默认只看 mobile/node_modules
// 且 hasteFS 只索引 projectRoot + watchFolders 内的文件。把仓库根纳入 watchFolders，
// 并显式补 nodeModulesPaths，metro 才能解析到 react-native / @babel/runtime 等。
const config = {
  watchFolders: [workspaceRoot, path.resolve(projectRoot, '../shared')],
  resolver: {
    nodeModulesPaths: [
      path.resolve(projectRoot, 'node_modules'),
      path.resolve(workspaceRoot, 'node_modules'),
    ],
    extraNodeModules: {
      '@diandi/shared': path.resolve(projectRoot, '../shared/src'),
    },
  },
};

module.exports = mergeConfig(getDefaultConfig(projectRoot), config);
