// The native safe-area view does not exist under Jest; use the library's own mock (zero insets).
jest.mock(
  'react-native-safe-area-context',
  () => require('react-native-safe-area-context/jest/mock').default,
)
