import {Route, Routes} from 'react-router-dom';

export const App = () => (
  <Routes>
    <Route path="/signup" element={<SignUp />} />
    <Route path="/orders/:id" element={<Order />} />
  </Routes>
);
