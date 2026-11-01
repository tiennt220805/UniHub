const preset = require('jest-expo/jest-preset')

/** @type {import('jest').Config} */
module.exports = {
  preset: 'jest-expo',
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/src/$1' },
  // The first React Native render of a file is slow on a cold CI runner (it took ~10 s emulated on amd64).
  testTimeout: 30_000,
  testPathIgnorePatterns: ['/node_modules/', '/.expo/'],
  // jest-expo's own list, plus the packages that ship ES modules only (@noble/*, drizzle-orm), which
  // must go through babel-preset-expo exactly as they do in the app bundle.
  transformIgnorePatterns: [
    preset.transformIgnorePatterns[0].replace('(.pnpm|', '(.pnpm|@noble|drizzle-orm|'),
    ...preset.transformIgnorePatterns.slice(1),
  ],
}
