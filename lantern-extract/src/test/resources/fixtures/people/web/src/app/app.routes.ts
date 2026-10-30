import {Routes} from '@angular/router';

export const routes: Routes = [
  {path: 'login', component: LoginComponent},
  {path: 'invitation/:invitationId', component: InvitationComponent},
  {path: '', component: HomeComponent, canActivate: [guard], children: [
    {path: 'users', component: UsersComponent},
  ]},
  {path: '**', redirectTo: ''},
];
