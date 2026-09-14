import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';
import { Theme } from '@radix-ui/themes';
import '@radix-ui/themes/styles.css';
import App from './App';
import { store } from './store';
import './index.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Provider store={store}>
      {/* Green reads as money without being a brand nobody has seen. */}
      <Theme accentColor="green" grayColor="slate" radius="medium" scaling="100%">
        <App />
      </Theme>
    </Provider>
  </StrictMode>,
);
